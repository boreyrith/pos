"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LogOut } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, useSyncExternalStore } from "react";

import { Button, Card, ErrorNote, Field, Input, Spinner, cx } from "@pos/shared/components/ui";
import { dateTime, money } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";

import { ShopHeader } from "@/components/ShopHeader";
import { guestOrders, shopApi, useAccount, type Account, type OrderSummary } from "@/lib/shop";

/** Sign in or create an account; signed in, your orders and Sign out. Guest orders on this device are listed too. */
export function AccountView() {
  const account = useAccount();
  return (
    <>
      <ShopHeader />
      <main className="mx-auto max-w-xl px-4 py-6">
        {account.isPending ? <Spinner /> : account.data ? <SignedIn account={account.data} /> : <SignInForms />}
        <GuestOrders />
      </main>
    </>
  );
}

function SignInForms() {
  const t = useT();
  const router = useRouter();
  const next = useSearchParams().get("next");
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [form, setForm] = useState({ name: "", phone: "", password: "" });
  const submit = useMutation({
    mutationFn: () =>
      mode === "signin"
        ? shopApi<Account>("/account/signin", { method: "POST", json: { phone: form.phone, password: form.password } })
        : shopApi<Account>("/account/signup", { method: "POST", json: form }),
    onSuccess: (a) => {
      queryClient.setQueryData(["shop", "account"], a);
      if (next && next.startsWith("/") && !next.startsWith("//")) router.push(next);
    },
  });
  return (
    <Card className="p-6">
      <div className="mb-5 flex rounded-xl border border-border p-1" role="tablist">
        {(["signin", "signup"] as const).map((m) => (
          <button
            key={m}
            role="tab"
            aria-selected={mode === m}
            onClick={() => setMode(m)}
            className={cx("h-9 flex-1 rounded-lg text-sm font-medium", mode === m ? "bg-primary-soft text-primary" : "text-muted")}
          >
            {m === "signin" ? t("Sign in") : t("Create an account")}
          </button>
        ))}
      </div>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          submit.mutate();
        }}
      >
        {mode === "signup" && (
          <Field label={t("Name")}>
            <Input required maxLength={150} autoComplete="name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
        )}
        <Field label={t("Phone")}>
          <Input required type="tel" maxLength={30} autoComplete="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        </Field>
        <Field label={t("Password")} hint={mode === "signup" ? t("At least 8 characters") : undefined}>
          <Input required type="password" minLength={mode === "signup" ? 8 : undefined} maxLength={100}
            autoComplete={mode === "signup" ? "new-password" : "current-password"}
            value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        <ErrorNote error={submit.error} />
        <Button type="submit" size="lg" loading={submit.isPending}>
          {mode === "signin" ? t("Sign in") : t("Create account")}
        </Button>
        {mode === "signup" ? (
          <p className="text-xs text-muted">{t("With an account you can see your orders, and they earn loyalty points if you are a member.")}</p>
        ) : (
          <p className="text-xs text-muted">{t("Forgot your password? Ask at the shop counter: staff can remove the account so you can create it again.")}</p>
        )}
      </form>
    </Card>
  );
}

function SignedIn({ account }: { account: Account }) {
  const t = useT();
  const queryClient = useQueryClient();
  const orders = useQuery({ queryKey: ["shop", "account", "orders"], queryFn: () => shopApi<OrderSummary[]>("/account/orders") });
  const signOut = useMutation({
    mutationFn: () => shopApi<void>("/account/signout", { method: "POST" }),
    onSuccess: () => queryClient.setQueryData(["shop", "account"], null),
  });
  return (
    <div className="flex flex-col gap-4">
      <Card className="flex items-center gap-3 p-4">
        <div className="min-w-0 flex-1">
          <div className="font-semibold">{account.name}</div>
          <div className="text-sm text-muted">{account.phone}</div>
        </div>
        <Button variant="secondary" size="sm" loading={signOut.isPending} onClick={() => signOut.mutate()}>
          <LogOut className="size-4" aria-hidden /> {t("Sign out")}
        </Button>
      </Card>
      {!account.verified && (
        <p className="rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning">
          {t("This phone number is already a customer of the shop. Show it at the counter to link this account; until then, online orders don't earn loyalty points.")}
        </p>
      )}
      <h2 className="text-lg font-semibold">{t("My orders")}</h2>
      {orders.isPending ? (
        <Spinner />
      ) : orders.error ? (
        <ErrorNote error={orders.error} />
      ) : orders.data.length === 0 ? (
        <p className="text-sm text-muted">{t("No orders yet.")}</p>
      ) : (
        <Card className="divide-y divide-border">
          {orders.data.map((o) => (
            <Link key={o.orderNumber} href={`/track/${o.orderNumber}`} className="flex items-center gap-3 p-3 hover:bg-surface-2">
              <div className="min-w-0 flex-1">
                <div className="font-mono text-sm font-medium">{o.orderNumber}</div>
                <div className="text-xs text-muted">{dateTime(o.createdAt)} · {t("{n} items", { n: o.items })}</div>
              </div>
              <span className="text-sm font-semibold">{money(o.total, o.currency)}</span>
            </Link>
          ))}
        </Card>
      )}
    </div>
  );
}

const noSubscribe = () => () => {};

/** Orders placed as a guest on this device (their private links are kept here). */
function GuestOrders() {
  const t = useT();
  const list = useSyncExternalStore(noSubscribe, () => JSON.stringify(guestOrders()), () => "[]");
  const orders = JSON.parse(list) as { orderNumber: string; key: string }[];
  if (orders.length === 0) return null;
  return (
    <div className="mt-6">
      <h2 className="mb-2 text-sm font-semibold text-muted">{t("Orders placed as a guest on this device")}</h2>
      <Card className="divide-y divide-border">
        {orders.map((o) => (
          <Link key={o.orderNumber} href={`/track/${o.orderNumber}?key=${encodeURIComponent(o.key)}`} className="block p-3 font-mono text-sm hover:bg-surface-2">
            {o.orderNumber}
          </Link>
        ))}
      </Card>
    </div>
  );
}
