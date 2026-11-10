"use client";

import { Field, Input, Select } from "@/components/ui";
import { msg, useT } from "@/lib/i18n";
import type { Gender, User } from "@/lib/types";

/** The optional personal details on a user account, as the form edits them (empty string: not given). */
export type PersonalDetailsForm = {
  nameKm: string;
  nameLatin: string;
  gender: "" | Gender;
  dateOfBirth: string;
  phone: string;
  email: string;
};

export const GENDER_LABELS: Record<Gender, string> = { MALE: msg("Male"), FEMALE: msg("Female") };

export function detailsForm(user?: User): PersonalDetailsForm {
  return {
    nameKm: user?.nameKm ?? "",
    nameLatin: user?.nameLatin ?? "",
    gender: user?.gender ?? "",
    dateOfBirth: user?.dateOfBirth ?? "",
    phone: user?.phone ?? "",
    email: user?.email ?? "",
  };
}

/** What the server takes: not given is null. */
export function detailsJson(f: PersonalDetailsForm) {
  return {
    nameKm: f.nameKm.trim() || null,
    nameLatin: f.nameLatin.trim() || null,
    gender: f.gender || null,
    dateOfBirth: f.dateOfBirth || null,
    phone: f.phone.trim() || null,
    email: f.email.trim() || null,
  };
}

/** Khmer and Latin names, gender, date of birth, phone and email, in a two-column grid. Used by user admins and in My account. */
export function PersonalDetailsFields({
  form,
  onChange,
  errors = {},
}: {
  form: PersonalDetailsForm;
  onChange: (form: PersonalDetailsForm) => void;
  errors?: Record<string, string>;
}) {
  const t = useT();
  const set = (k: keyof PersonalDetailsForm) => (v: string) => onChange({ ...form, [k]: v });
  return (
    <>
      <Field label={t("Khmer name")} error={errors.nameKm}>
        <Input value={form.nameKm} maxLength={100} lang="km" onChange={(e) => set("nameKm")(e.target.value)} />
      </Field>
      <Field label={t("Latin name")} hint={t("As on their ID card")} error={errors.nameLatin}>
        <Input value={form.nameLatin} maxLength={100} onChange={(e) => set("nameLatin")(e.target.value)} />
      </Field>
      <Field label={t("Gender")} error={errors.gender}>
        <Select value={form.gender} onChange={(e) => set("gender")(e.target.value)}>
          <option value="">{t("Not given")}</option>
          {(Object.keys(GENDER_LABELS) as Gender[]).map((g) => (
            <option key={g} value={g}>
              {t(GENDER_LABELS[g])}
            </option>
          ))}
        </Select>
      </Field>
      <Field label={t("Date of birth")} error={errors.dateOfBirth}>
        <Input type="date" value={form.dateOfBirth} min="1900-01-01" onChange={(e) => set("dateOfBirth")(e.target.value)} />
      </Field>
      <Field label={t("Phone")} error={errors.phone}>
        <Input type="tel" value={form.phone} maxLength={50} onChange={(e) => set("phone")(e.target.value)} />
      </Field>
      <Field label={t("Email")} error={errors.email}>
        <Input type="email" value={form.email} maxLength={100} onChange={(e) => set("email")(e.target.value)} />
      </Field>
    </>
  );
}
