"use client";

import { useState } from "react";

import { Button, ErrorNote, Field, Input } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useT } from "@/lib/i18n";

/** Change your own password. Signs out your other devices. */
export function ChangePasswordForm({ onDone, submitLabel }: { onDone?: () => void; submitLabel?: string }) {
  const { changePassword } = useAuth();
  const t = useT();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);

  const tooShort = next.length > 0 && next.length < 8;
  const mismatch = repeat.length > 0 && repeat !== next;
  const valid = current && next.length >= 8 && next === repeat;

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!valid) return;
        setSaving(true);
        setError(null);
        try {
          await changePassword(current, next);
          setDone(true);
          setCurrent("");
          setNext("");
          setRepeat("");
          onDone?.();
        } catch (err) {
          setError(err);
        } finally {
          setSaving(false);
        }
      }}
    >
      <Field label={t("Current password")}>
        <Input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
      </Field>
      <Field label={t("New password")} hint={t("At least 8 characters")} error={tooShort ? t("At least 8 characters") : undefined}>
        <Input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} invalid={tooShort} required />
      </Field>
      <Field label={t("Repeat new password")} error={mismatch ? t("The passwords don't match") : undefined}>
        <Input type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} invalid={mismatch} required />
      </Field>
      <ErrorNote error={error} />
      {done && <p className="rounded-lg bg-success-soft px-3 py-2 text-sm text-success">{t("Password changed. Your other devices were signed out.")}</p>}
      <Button type="submit" disabled={!valid} loading={saving}>
        {submitLabel ?? t("Change password")}
      </Button>
    </form>
  );
}
