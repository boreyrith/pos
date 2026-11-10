"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Download } from "lucide-react";
import { useState } from "react";

import { Badge, Button, ErrorNote, Field, Input, Modal, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import type { CouponCode, Promotion } from "@/lib/types";

import { promotionsKey } from "./PromotionsTab";

/** A promotion's coupon codes: who used them, adding one, generating a batch, turning codes off. */
export function CouponsDialog({ promotion, onClose }: { promotion: Promotion; onClose: () => void }) {
  const t = useT();
  const manage = useCan()("PROMOTIONS_MANAGE");
  const queryClient = useQueryClient();
  const key = [...promotionsKey, promotion.id, "codes"];
  const codes = useQuery({ queryKey: key, queryFn: () => api<CouponCode[]>(`/promotions/${promotion.id}/codes`) });
  const [mode, setMode] = useState<"one" | "batch">("one");
  const [code, setCode] = useState("");
  const [maxUses, setMaxUses] = useState("");
  const [expiresOn, setExpiresOn] = useState("");
  const [count, setCount] = useState("20");
  const [prefix, setPrefix] = useState("");
  const [made, setMade] = useState<CouponCode[] | null>(null);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: key });
    queryClient.invalidateQueries({ queryKey: promotionsKey });
  };
  const add = useMutation({
    mutationFn: () =>
      api<CouponCode>(`/promotions/${promotion.id}/codes`, {
        method: "POST",
        json: { code: code.trim(), maxUses: maxUses ? Number(maxUses) : null, expiresOn: expiresOn || null },
      }),
    onSuccess: () => {
      setCode("");
      refresh();
    },
  });
  const generate = useMutation({
    mutationFn: () =>
      api<CouponCode[]>(`/promotions/${promotion.id}/codes/generate`, {
        method: "POST",
        json: { count: Number(count), prefix: prefix.trim() || null, maxUses: maxUses ? Number(maxUses) : null, expiresOn: expiresOn || null },
      }),
    onSuccess: (list) => {
      setMade(list);
      refresh();
    },
  });
  const toggle = useMutation({
    mutationFn: (c: CouponCode) =>
      api<CouponCode>(`/promotions/${promotion.id}/codes/${c.id}`, { method: "PUT", json: { active: !c.active, expiresOn: c.expiresOn } }),
    onSuccess: refresh,
  });

  return (
    <Modal open onClose={onClose} title={t("Coupon codes: {name}", { name: promotion.name })} wide>
      <div className="flex flex-col gap-5">
        {manage && (
          <section className="rounded-xl border border-border p-4">
            <div className="mb-3 inline-flex rounded-full bg-surface-2 p-1 ring-1 ring-border">
              {(["one", "batch"] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  aria-pressed={mode === m}
                  onClick={() => setMode(m)}
                  className={cx(
                    "rounded-full px-3 py-1 text-sm font-medium",
                    mode === m ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
                  )}
                >
                  {m === "one" ? t("One code") : t("Generate many")}
                </button>
              ))}
            </div>
            <form
              className="grid gap-3 sm:grid-cols-4"
              onSubmit={(e) => {
                e.preventDefault();
                if (mode === "one") add.mutate();
                else generate.mutate();
              }}
            >
              {mode === "one" ? (
                <Field label={t("Code")}>
                  <Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="SUMMER10" className="font-mono uppercase" required minLength={3} maxLength={40} />
                </Field>
              ) : (
                <>
                  <Field label={t("How many")}>
                    <Input type="number" min={1} max={1000} value={count} onChange={(e) => setCount(e.target.value)} required />
                  </Field>
                  <Field label={t("Prefix (optional)")}>
                    <Input value={prefix} onChange={(e) => setPrefix(e.target.value.toUpperCase())} placeholder="FLYER" className="font-mono uppercase" maxLength={12} />
                  </Field>
                </>
              )}
              <Field label={t("Uses per code")} hint={mode === "one" ? t("Blank: any number") : t("Blank: once each")}>
                <Input type="number" min={1} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
              </Field>
              <Field label={t("Expires (optional)")}>
                <Input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} />
              </Field>
              <div className="flex items-end sm:col-span-4">
                <Button type="submit" loading={add.isPending || generate.isPending}>
                  {mode === "one" ? t("Add code") : t("Generate codes")}
                </Button>
              </div>
            </form>
            <ErrorNote error={add.error ?? generate.error} />
            {made && made.length > 0 && <MadeCodes codes={made} name={promotion.name} />}
          </section>
        )}

        {codes.isPending ? (
          <Spinner />
        ) : codes.error ? (
          <ErrorNote error={codes.error} />
        ) : codes.data.length === 0 ? (
          <p className="text-sm text-muted">{t("No codes yet.")}</p>
        ) : (
          <div className="max-h-80 overflow-y-auto">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-surface text-left text-xs text-muted">
                <tr>
                  <th className="py-2 font-medium">{t("Code")}</th>
                  <th className="py-2 text-right font-medium">{t("Used")}</th>
                  <th className="hidden py-2 font-medium sm:table-cell">{t("Customer")}</th>
                  <th className="hidden py-2 font-medium sm:table-cell">{t("Expires")}</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {codes.data.map((c) => (
                  <tr key={c.id} className="border-t border-border">
                    <td className="py-2 font-mono">{c.code}</td>
                    <td className="py-2 text-right tabular-nums">{c.maxUses ? `${c.usedCount} / ${c.maxUses}` : c.usedCount}</td>
                    <td className="hidden py-2 sm:table-cell">{c.customerName ?? "—"}</td>
                    <td className="hidden py-2 sm:table-cell">{c.expiresOn ?? "—"}</td>
                    <td className="py-2 text-right">
                      {!c.active ? (
                        <Badge tone="neutral">{t("Off")}</Badge>
                      ) : c.maxUses != null && c.usedCount >= c.maxUses ? (
                        <Badge tone="warning">{t("Used up")}</Badge>
                      ) : null}
                      {manage && (
                        <button onClick={() => toggle.mutate(c)} className="ml-2 text-xs text-primary hover:underline">
                          {c.active ? t("Turn off") : t("Turn on")}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Modal>
  );
}

/** Codes just generated: copy them, or download them for printing. */
function MadeCodes({ codes, name }: { codes: CouponCode[]; name: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const text = codes.map((c) => c.code).join("\n");
  const download = () => {
    const csv = "code,uses,expires\n" + codes.map((c) => `${c.code},${c.maxUses ?? ""},${c.expiresOn ?? ""}`).join("\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `${name.replace(/[^\w-]+/g, "-")}-codes.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <div className="mt-3 rounded-lg bg-success-soft p-3 text-sm text-success">
      <p className="font-medium">{t("{n} codes ready", { n: codes.length })}</p>
      <div className="mt-2 flex gap-2">
        <Button
          size="sm"
          variant="secondary"
          onClick={() => navigator.clipboard?.writeText(text).then(() => setCopied(true), () => setCopied(false))}
        >
          <Copy className="size-4" aria-hidden /> {copied ? t("Copied") : t("Copy all")}
        </Button>
        <Button size="sm" variant="secondary" onClick={download}>
          <Download className="size-4" aria-hidden /> {t("Download CSV")}
        </Button>
      </div>
    </div>
  );
}
