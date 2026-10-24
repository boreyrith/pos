"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ImageUp, Trash2 } from "lucide-react";
import { useRef, useState } from "react";

import { Button, Card, ErrorNote, Field, Input, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { logoUrl } from "@/lib/queries";
import type { CompanySettings } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

/** Company details, receipt text and time zone. Admins edit. Tax and KHQR have their own tabs. */
export function CompanyPanel() {
  const company = useQuery({ queryKey: ["settings", "company"], queryFn: () => api<CompanySettings>("/settings/company") });
  if (company.isPending) return <Spinner />;
  if (company.error) return <ErrorNote error={company.error} />;
  return <CompanyForm key={company.data.updatedAt} company={company.data} />;
}

const TIMEZONES = ["Asia/Phnom_Penh", "Asia/Bangkok", "Asia/Ho_Chi_Minh", "Asia/Vientiane", "Asia/Singapore", "Asia/Kuala_Lumpur", "UTC"];

function CompanyForm({ company }: { company: CompanySettings }) {
  const t = useT();
  const queryClient = useQueryClient();
  const admin = hasPermission(useUser(), "SETTINGS_COMPANY");
  const [form, setForm] = useState({
    name: company.name,
    legalName: company.legalName ?? "",
    taxId: company.taxId ?? "",
    address: company.address ?? "",
    phone: company.phone ?? "",
    email: company.email ?? "",
    website: company.website ?? "",
    receiptFooter: company.receiptFooter ?? "",
    timezone: company.timezone,
  });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["settings"] });
    queryClient.invalidateQueries({ queryKey: ["store"] });
  };
  const save = useMutation({
    mutationFn: () => api<CompanySettings>("/settings/company", { method: "PUT", json: form }),
    onSuccess: refresh,
  });
  const upload = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("file", file);
      return api<CompanySettings>("/settings/company/logo", { method: "PUT", body });
    },
    onSuccess: refresh,
  });
  const removeLogo = useMutation({
    mutationFn: () => api<CompanySettings>("/settings/company/logo", { method: "DELETE" }),
    onSuccess: refresh,
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const tzChanged = form.timezone !== company.timezone;

  const field = (key: keyof typeof form, label: string, opts: { hint?: string; max?: number; placeholder?: string; error?: string } = {}) => (
    <Field label={t(label)} hint={t(opts.hint)} error={opts.error ?? errors[key]}>
      <Input
        value={form[key]}
        onChange={(e) => set(key)(e.target.value)}
        maxLength={opts.max}
        placeholder={t(opts.placeholder)}
        disabled={!admin}
      />
    </Field>
  );

  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      {!admin && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">{t("Your role can view company settings but not change them.")}</p>}

      <Card className="p-5">
        <h2 className="mb-4 text-sm font-semibold">{t("Company")}</h2>
        <div className="mb-5 flex items-center gap-4">
          <div className="grid size-20 shrink-0 place-items-center overflow-hidden rounded-xl border border-border bg-white">
            {company.hasLogo ? (
              // eslint-disable-next-line @next/next/no-img-element -- served by our API, sized by CSS
              <img src={logoUrl(company.logoVersion)} alt={t("Company logo")} className="max-h-full max-w-full object-contain" />
            ) : (
              <span className="text-xs text-muted">{t("No logo")}</span>
            )}
          </div>
          {admin && (
            <div className="flex flex-col gap-2">
              <input
                ref={fileRef}
                type="file"
                accept="image/png,image/jpeg,image/webp"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) upload.mutate(file);
                  e.target.value = "";
                }}
              />
              <div className="flex gap-2">
                <Button type="button" variant="secondary" size="sm" onClick={() => fileRef.current?.click()} loading={upload.isPending}>
                  <ImageUp className="size-4" aria-hidden /> {company.hasLogo ? t("Replace logo") : t("Upload logo")}
                </Button>
                {company.hasLogo && (
                  <Button type="button" variant="ghost" size="sm" onClick={() => removeLogo.mutate()} loading={removeLogo.isPending}>
                    <Trash2 className="size-4" aria-hidden />{" "}{t("Remove")}
                  </Button>
                )}
              </div>
              <span className="text-xs text-muted">{t("PNG, JPEG or WebP, up to 512 KB. Printed on receipts.")}</span>
              <ErrorNote error={upload.error ?? removeLogo.error} />
            </div>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          {field("name", msg("Trading name"), { hint: msg("Shown on screens and receipts"), max: 100 })}
          {field("legalName", msg("Legal name"), { placeholder: msg("e.g. Rith Trading Co., Ltd."), max: 150 })}
          {field("taxId", msg("VAT TIN / tax ID"), { hint: msg("Printed on receipts; also under Taxes"), max: 50 })}
          {field("phone", msg("Phone"), { max: 50 })}
          <div className="sm:col-span-2">{field("address", msg("Address"), { max: 255 })}</div>
          {field("email", msg("Email"), { max: 100 })}
          {field("website", msg("Website"), { max: 150 })}
        </div>
      </Card>

      <Card className="p-5">
        <h2 className="mb-4 text-sm font-semibold">{t("Sales and receipts")}</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t("Time zone")}
            error={errors.timezone}
            hint={tzChanged ? t("Changes which day sales count towards in reports and order numbers.") : t("Used for business days, reports and order numbers")}
          >
            <Input list="timezones" value={form.timezone} onChange={(e) => set("timezone")(e.target.value)} disabled={!admin} />
            <datalist id="timezones">
              {TIMEZONES.map((tz) => (
                <option key={tz} value={tz} />
              ))}
            </datalist>
          </Field>
          <div className="sm:col-span-2">
            <Field label={t("Receipt footer")} error={errors.receiptFooter} hint={t("e.g. thanks, return policy, Wi-Fi password")}>
              <textarea
                value={form.receiptFooter}
                onChange={(e) => set("receiptFooter")(e.target.value)}
                maxLength={500}
                rows={3}
                disabled={!admin}
                className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text focus:outline-2 focus:outline-primary"
              />
            </Field>
          </div>
        </div>
      </Card>


      <ErrorNote error={save.error} />
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-muted">
          {t("Last changed {updatedAt}", { updatedAt: dateTime(company.updatedAt, company.timezone) })}
          {company.updatedBy ? t(" by {updatedBy}", { updatedBy: company.updatedBy }) : ""}
        </span>
        {admin && (
          <Button type="submit" loading={save.isPending}>
            {t("Save settings")}
          </Button>
        )}
      </div>
    </form>
  );
}
