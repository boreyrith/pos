"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { Button, ErrorNote, Field, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import type { Customer } from "@/lib/types";
import { useT } from "@/lib/i18n";

/** Adds a customer, or edits one. `initialName` pre-fills a quick add from a search. */
export function CustomerDialog({
  customer,
  initialName = "",
  onClose,
  onSaved,
}: {
  customer?: Customer;
  initialName?: string;
  onClose: () => void;
  onSaved?: (c: Customer) => void;
}) {
  const t = useT();
  const queryClient = useQueryClient();
  // A search that looks like a phone number fills the phone instead of the name.
  const looksLikePhone = /^[+\d][\d\s-]{5,}$/.test(initialName.trim());
  const [form, setForm] = useState({
    name: customer?.name ?? (looksLikePhone ? "" : initialName),
    phone: customer?.phone ?? (looksLikePhone ? initialName.trim() : ""),
    email: customer?.email ?? "",
    address: customer?.address ?? "",
    taxId: customer?.taxId ?? "",
    note: customer?.note ?? "",
    active: customer?.active ?? true,
  });
  const set = (k: keyof typeof form) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () => {
      const body = {
        ...form,
        name: form.name.trim(),
        phone: form.phone || null,
        email: form.email || null,
        address: form.address || null,
        taxId: form.taxId || null,
        note: form.note || null,
      };
      return customer
        ? api<Customer>(`/customers/${customer.id}`, { method: "PUT", json: body })
        : api<Customer>("/customers", { method: "POST", json: body });
    },
    onSuccess: (c) => {
      queryClient.invalidateQueries({ queryKey: ["customers"] });
      onSaved?.(c);
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  return (
    <Modal open onClose={onClose} title={customer ? t("Edit {name}", { name: customer.name }) : t("New customer")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="col-span-2">
          <Field label={t("Name")} error={errors.name}>
            <Input autoFocus required maxLength={150} value={form.name} onChange={(e) => set("name")(e.target.value)} />
          </Field>
        </div>
        <Field label={t("Phone")} error={errors.phone} hint={t("Used to find them quickly; must be unique")}>
          <Input inputMode="tel" maxLength={30} value={form.phone} onChange={(e) => set("phone")(e.target.value)} />
        </Field>
        <Field label={t("Email")} error={errors.email}>
          <Input type="email" maxLength={100} value={form.email} onChange={(e) => set("email")(e.target.value)} />
        </Field>
        <div className="col-span-2">
          <Field label={t("Address")} error={errors.address}>
            <Input maxLength={255} value={form.address} onChange={(e) => set("address")(e.target.value)} />
          </Field>
        </div>
        <Field label={t("VAT TIN")} error={errors.taxId} hint={t("For business customers; printed on receipts")}>
          <Input maxLength={50} value={form.taxId} onChange={(e) => set("taxId")(e.target.value)} />
        </Field>
        <Field label={t("Note")} error={errors.note}>
          <Input maxLength={500} value={form.note} onChange={(e) => set("note")(e.target.value)} />
        </Field>
        {customer && !customer.walkIn && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.active} onChange={(e) => set("active")(e.target.checked)} />
            {t("Active (inactive customers can't be picked for new sales)")}
          </label>
        )}
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!form.name.trim()}>
            {customer ? t("Save") : t("Add customer")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
