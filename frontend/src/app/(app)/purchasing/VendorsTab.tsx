"use client";

import { useMutation } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Vendor } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { useRefreshPurchasing, useVendors } from "./shared";

export function VendorsTab() {
  const t = useT();
  const can = useCan();
  const vendors = useVendors();
  const store = useStoreSettings();
  const [editing, setEditing] = useState<Vendor | "new" | null>(null);
  const [q, setQ] = useState("");
  const currency = store.data?.currency ?? "USD";
  const term = q.trim().toLowerCase();
  const shown = (vendors.data ?? []).filter(
    (v) => !term || [v.name, v.code, v.contactName ?? "", v.phone ?? "", v.email ?? ""].some((x) => x.toLowerCase().includes(term)),
  );

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input placeholder={t("Search vendors")} aria-label={t("Search vendors")} value={q} onChange={(e) => setQ(e.target.value)} className="w-64" />
        {can("VENDORS_MANAGE") && (
          <Button className="ml-auto" onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden />{" "}{t("Add vendor")}
          </Button>
        )}
      </div>
      <Card className="overflow-x-auto">
        {vendors.isPending ? (
          <Spinner />
        ) : vendors.error ? (
          <div className="p-4">
            <ErrorNote error={vendors.error} />
          </div>
        ) : shown.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No vendors yet.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Vendor")}</th>
                <th className="px-4 py-3 font-medium">{t("Contact")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Terms")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Open orders")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Owed")}</th>
                <th className="px-4 py-3" aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {shown.map((v) => (
                <tr key={v.id} className={v.active ? "" : "text-muted"}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2 font-medium">
                      {v.name}
                      {!v.active && <Badge>{t("Inactive")}</Badge>}
                    </div>
                    <div className="font-mono text-xs text-muted">
                      {v.code}
                      {v.taxId && ` · TIN ${v.taxId}`}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {v.contactName && <div className="text-sm">{v.contactName}</div>}
                    <div className="text-muted">{[v.phone, v.email].filter(Boolean).join(" · ")}</div>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{v.paymentTermsDays === 0 ? t("On receipt") : t("{paymentTermsDays} days", { paymentTermsDays: v.paymentTermsDays })}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{v.openOrders}</td>
                  <td className={`px-4 py-3 text-right tabular-nums ${v.balance > 0 ? "font-medium" : "text-muted"}`}>{money(v.balance, currency)}</td>
                  <td className="px-4 py-3 text-right">
                    {can("VENDORS_MANAGE") && (
                      <Button size="sm" variant="ghost" onClick={() => setEditing(v)}>
                        {t("Edit")}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {editing && <VendorDialog vendor={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function VendorDialog({ vendor, onClose }: { vendor: Vendor | null; onClose: () => void }) {
  const t = useT();
  const refresh = useRefreshPurchasing();
  const [form, setForm] = useState({
    code: vendor?.code ?? "",
    name: vendor?.name ?? "",
    contactName: vendor?.contactName ?? "",
    phone: vendor?.phone ?? "",
    email: vendor?.email ?? "",
    address: vendor?.address ?? "",
    taxId: vendor?.taxId ?? "",
    paymentTermsDays: String(vendor?.paymentTermsDays ?? 0),
    note: vendor?.note ?? "",
    active: vendor?.active ?? true,
  });
  const set = (k: keyof typeof form) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () => {
      const body = {
        ...form,
        paymentTermsDays: Number(form.paymentTermsDays) || 0,
        contactName: form.contactName || null,
        phone: form.phone || null,
        email: form.email || null,
        address: form.address || null,
        taxId: form.taxId || null,
        note: form.note || null,
      };
      return vendor ? api<Vendor>(`/vendors/${vendor.id}`, { method: "PUT", json: body }) : api<Vendor>("/vendors", { method: "POST", json: body });
    },
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const field = (k: Exclude<keyof typeof form, "active">, label: string, opts: { max?: number; hint?: string; type?: string; required?: boolean } = {}) => (
    <Field label={t(label)} error={errors[k]} hint={t(opts.hint)}>
      <Input type={opts.type} required={opts.required} maxLength={opts.max} value={form[k]} onChange={(e) => set(k)(e.target.value)} />
    </Field>
  );
  return (
    <Modal open onClose={onClose} title={vendor ? t("Edit {name}", { name: vendor.name }) : t("Add vendor")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {field("code", msg("Code"), { max: 20, hint: msg("Short and unique, e.g. V-COFFEE"), required: true })}
        {field("name", msg("Name"), { max: 150, required: true })}
        {field("contactName", msg("Contact person"), { max: 100 })}
        {field("phone", msg("Phone"), { max: 50 })}
        {field("email", msg("Email"), { max: 100, type: "email" })}
        {field("taxId", msg("VAT TIN"), { max: 50 })}
        <div className="col-span-2">{field("address", msg("Address"), { max: 255 })}</div>
        <Field label={t("Payment terms (days)")} error={errors.paymentTermsDays} hint={t("0 = pay on receipt")}>
          <Input inputMode="numeric" value={form.paymentTermsDays} onChange={(e) => set("paymentTermsDays")(e.target.value.replace(/\D/g, ""))} />
        </Field>
        {field("note", msg("Note"), { max: 500 })}
        {vendor && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.active} onChange={(e) => set("active")(e.target.checked)} />
            {t("Active (inactive vendors can't get new orders)")}
          </label>
        )}
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {vendor ? t("Save") : t("Add vendor")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
