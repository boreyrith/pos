"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, PlugZap, XCircle } from "lucide-react";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Spinner, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { KhqrSettings } from "@/lib/types";
import { msg, translateServer, useT } from "@/lib/i18n";

const DAY = 24 * 60 * 60 * 1000;
const BAKONG_URLS = [
  { url: "https://api-bakong.nbc.gov.kh", label: msg("Production") },
  { url: "https://sit-api-bakong.nbc.gov.kh", label: msg("Sandbox (SIT)") },
];

type TestResult = { status: "OK" | "UNAUTHORIZED" | "UNREACHABLE" | "ERROR"; message: string };

/**
 * Settings > KHQR payments: the account customers pay into, what the QR code shows, test or live
 * mode, and the Bakong API connection. Admins change it; the token is write-only.
 */
export function KhqrPanel() {
  const settings = useQuery({ queryKey: ["settings", "khqr"], queryFn: () => api<KhqrSettings>("/settings/khqr") });
  if (settings.isPending) return <Spinner />;
  if (settings.error) return <ErrorNote error={settings.error} />;
  return <KhqrForm key={settings.data.updatedAt + settings.data.tokenSource + settings.data.mode} settings={settings.data} />;
}

function KhqrForm({ settings }: { settings: KhqrSettings }) {
  const t = useT();
  const admin = hasPermission(useUser(), "SETTINGS_KHQR");
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    mode: settings.mode,
    accountId: settings.accountId,
    merchantName: settings.merchantName,
    merchantCity: settings.merchantCity,
    apiBaseUrl: settings.apiBaseUrl,
    expiryMinutes: String(settings.expiryMinutes),
  });
  const [token, setToken] = useState("");
  const [removeToken, setRemoveToken] = useState(false);
  // Read once when the form opens, to count the token's days left.
  const [now] = useState(() => Date.now());
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));

  const save = useMutation({
    mutationFn: () =>
      api<KhqrSettings>("/settings/khqr", {
        method: "PUT",
        json: { ...form, expiryMinutes: Number(form.expiryMinutes), apiToken: token.trim() || null, removeToken },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["settings", "khqr"] });
      queryClient.invalidateQueries({ queryKey: ["store"] });
    },
  });
  const test = useMutation({
    mutationFn: () =>
      api<TestResult>("/settings/khqr/test", { method: "POST", json: { apiBaseUrl: form.apiBaseUrl, apiToken: token.trim() || null } }),
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};

  const expiry = Number(form.expiryMinutes);
  const expiryInvalid = !Number.isInteger(expiry) || expiry < 1 || expiry > 60;
  const willHaveToken = token.trim() !== "" || (!removeToken && settings.tokenSource === "SAVED") || settings.tokenSource === "SERVER";
  const liveWithoutToken = form.mode === "BAKONG" && !willHaveToken;
  const goingLive = form.mode === "BAKONG" && settings.mode !== "BAKONG";

  const expiresAt = settings.tokenExpiresAt ? new Date(settings.tokenExpiresAt) : null;
  const daysLeft = expiresAt ? Math.floor((expiresAt.getTime() - now) / DAY) : null;
  const tz = store.data?.timezone;

  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault();
        if (goingLive && !confirm(t("Switch to live payments? KHQR codes will charge real money into {account}.", { account: form.accountId }))) return;
        save.mutate();
      }}
    >
      {!admin && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">{t("Your role can view KHQR settings but not change them.")}</p>}
      <fieldset disabled={!admin} className="flex flex-col gap-5">
        <Card className="p-5">
          <h2 className="mb-3 text-sm font-semibold">{t("Mode")}</h2>
          <div role="radiogroup" className="grid gap-3 md:grid-cols-2">
            {(
              [
                ["SIMULATED", msg("Test (simulated)"), msg("QR codes are generated but no money moves. Cashiers confirm the payment by hand. For training and setup.")],
                ["BAKONG", msg("Live (Bakong)"), msg("Customers pay with their banking app. The till checks Bakong and completes the sale when the money arrives.")],
              ] as const
            ).map(([mode, title, body]) => (
              <label
                key={mode}
                className={cx(
                  "flex cursor-pointer flex-col gap-1 rounded-xl border p-4 text-sm",
                  form.mode === mode ? "border-primary bg-primary-soft" : "border-border hover:bg-surface-2",
                )}
              >
                <span className="flex items-center gap-2 font-medium">
                  <input type="radio" name="khqrMode" checked={form.mode === mode} onChange={() => set("mode", mode)} />
                  {t(title)}
                </span>
                <span className="text-xs text-muted">{t(body)}</span>
              </label>
            ))}
          </div>
          {!settings.modeSaved && <p className="mt-2 text-xs text-muted">{t("Currently following the server default (KHQR_MODE).")}</p>}
          {liveWithoutToken && <p className="mt-2 text-sm text-danger">{t("Add the Bakong API token below to take live payments.")}</p>}
        </Card>

        <Card className="p-5">
          <h2 className="mb-1 text-sm font-semibold">{t("Receiving account")}</h2>
          <p className="mb-4 text-xs text-muted">{t("Where customers' payments go, and what their banking app shows when they scan.")}</p>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("Bakong account ID")} error={errors.accountId} hint={t("e.g. shopname@aclb")}>
              <Input value={form.accountId} maxLength={100} placeholder={t("name@bank")} onChange={(e) => set("accountId", e.target.value)} />
            </Field>
            <Field label={t("Merchant name")} error={errors.merchantName} hint={t("Up to 25 characters")}>
              <Input value={form.merchantName} maxLength={25} onChange={(e) => set("merchantName", e.target.value)} />
            </Field>
            <Field label={t("City")} error={errors.merchantCity} hint={t("Up to 15 characters")}>
              <Input value={form.merchantCity} maxLength={15} onChange={(e) => set("merchantCity", e.target.value)} />
            </Field>
            <Field
              label={t("Code valid for (minutes)")}
              error={errors.expiryMinutes ?? (expiryInvalid ? "1 to 60" : undefined)}
              hint={settings.expirySaved ? t("Unpaid codes expire and the stock is released") : t("Server default")}
            >
              <Input inputMode="numeric" value={form.expiryMinutes} onChange={(e) => set("expiryMinutes", e.target.value.replace(/\D/g, ""))} />
            </Field>
          </div>
        </Card>

        <Card className="p-5">
          <h2 className="mb-1 text-sm font-semibold">{t("Bakong API")}</h2>
          <p className="mb-4 text-xs text-muted">
            {t("Used to check whether a KHQR payment arrived. Register for a token on the Bakong API portal (api-bakong.nbc.gov.kh); tokens expire after 90 days and must be renewed.")}
          </p>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={t("API address")} error={errors.apiBaseUrl} hint={settings.apiBaseUrlSaved ? undefined : t("Server default (BAKONG_API_URL)")}>
              <Input value={form.apiBaseUrl} maxLength={255} onChange={(e) => set("apiBaseUrl", e.target.value)} />
            </Field>
            <div className="flex flex-wrap items-end gap-2 pb-6">
              {BAKONG_URLS.map((u) => (
                <Button key={u.url} type="button" size="sm" variant="ghost" onClick={() => set("apiBaseUrl", u.url)}>
                  {t("Use {label}", { label: u.label })}
                </Button>
              ))}
            </div>
          </div>

          <div className="mt-2 rounded-lg border border-border p-4">
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">{t("API token")}</span>
              {settings.tokenSource === "SAVED" && <Badge tone="success">{t("Saved ····{tokenHint}", { tokenHint: settings.tokenHint })}</Badge>}
              {settings.tokenSource === "SERVER" && <Badge>{t("From server (BAKONG_TOKEN) ····{tokenHint}", { tokenHint: settings.tokenHint })}</Badge>}
              {settings.tokenSource === "NONE" && <Badge tone="warning">{t("Not set")}</Badge>}
              {expiresAt &&
                (daysLeft! < 0 ? (
                  <Badge tone="danger">{t("Expired {toISOString}", { toISOString: dateTime(expiresAt.toISOString(), tz) })}</Badge>
                ) : (
                  <Badge tone={daysLeft! <= 14 ? "warning" : "neutral"}>
                    {t("Expires {toISOString} ({daysLeft} days)", { toISOString: dateTime(expiresAt.toISOString(), tz), daysLeft })}
                  </Badge>
                ))}
              {settings.tokenUpdatedAt && <span className="text-xs text-muted">{t("changed {tokenUpdatedAt}", { tokenUpdatedAt: dateTime(settings.tokenUpdatedAt, tz) })}</span>}
            </div>
            <Field
              label={settings.tokenSource === "NONE" ? t("Paste the token") : t("Replace token")}
              hint={t("Kept encrypted on the server and never shown again. Leave empty to keep the current one.")}
            >
              <Input
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={token}
                maxLength={4000}
                placeholder={settings.tokenSource === "NONE" ? t("eyJhbGciOi...") : "••••••••"}
                onChange={(e) => {
                  setToken(e.target.value);
                  if (e.target.value) setRemoveToken(false);
                  test.reset();
                }}
              />
            </Field>
            {settings.tokenSource === "SAVED" && !token && (
              <label className="mt-3 flex items-center gap-2 text-sm">
                <input type="checkbox" checked={removeToken} onChange={(e) => setRemoveToken(e.target.checked)} />
                {t("Remove the saved token")}
              </label>
            )}
            {admin && (
              <div className="mt-3 flex flex-wrap items-center gap-3">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => test.mutate()}
                  loading={test.isPending}
                  disabled={!token.trim() && settings.tokenSource === "NONE"}
                >
                  <PlugZap className="size-4" aria-hidden />{" "}{t("Test connection")}
                </Button>
                {test.data &&
                  (test.data.status === "OK" ? (
                    <span className="flex items-center gap-1 text-sm text-success">
                      <CheckCircle2 className="size-4" aria-hidden /> {translateServer(test.data.message)}
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-sm text-danger">
                      <XCircle className="size-4" aria-hidden /> {translateServer(test.data.message)}
                    </span>
                  ))}
                <ErrorNote error={test.error} />
              </div>
            )}
          </div>
        </Card>
      </fieldset>

      <ErrorNote error={save.error} />
      {save.isSuccess && !save.isPending && <p className="text-sm text-success">{t("Saved. New KHQR payments use these settings.")}</p>}
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-muted">
          {t("Last changed {updatedAt}", { updatedAt: dateTime(settings.updatedAt, tz) })}
          {settings.updatedBy ? t(" by {updatedBy}", { updatedBy: settings.updatedBy }) : ""}
        </span>
        {admin && (
          <Button type="submit" loading={save.isPending} disabled={expiryInvalid || liveWithoutToken}>
            {t("Save KHQR settings")}
          </Button>
        )}
      </div>
    </form>
  );
}
