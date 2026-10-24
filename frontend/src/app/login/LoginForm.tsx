"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

import { LanguageSwitch } from "@/components/LanguageSwitch";
import { Button, Card, ErrorNote, Field, Input } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useT } from "@/lib/i18n";

export function LoginForm() {
  const { state, login } = useAuth();
  const t = useT();
  const router = useRouter();
  const params = useSearchParams();
  const next = params.get("next");
  // Only follow same-site paths, never a full URL from the query string.
  const destination = next && next.startsWith("/") && !next.startsWith("//") ? next : "/pos";

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [logoFailed, setLogoFailed] = useState(false);

  useEffect(() => {
    if (state.status === "signed-in") router.replace(destination);
  }, [state.status, router, destination]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
    } catch (err) {
      setError(err);
      setSubmitting(false);
    }
  }

  return (
    <Card className="w-full max-w-sm p-6 shadow-sm">
      <div className="mb-6 flex items-center gap-3">
        {logoFailed ? (
          <div className="grid size-10 place-items-center rounded-xl bg-primary text-sm font-bold text-on-primary">POS</div>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element -- the company logo, public; falls back to the badge
          <img src="/api/settings/company/logo" alt="" onError={() => setLogoFailed(true)} className="size-10 rounded-xl object-contain" />
        )}
        <div>
          <h1 className="text-lg font-semibold">{t("Sign in")}</h1>
          <p className="text-sm text-muted">{t("Use your staff account")}</p>
        </div>
      </div>
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <Field label={t("Username")}>
          <Input
            autoFocus
            autoComplete="username"
            autoCapitalize="none"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </Field>
        <Field label={t("Password")}>
          <Input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </Field>
        <ErrorNote error={error} />
        <Button type="submit" size="lg" loading={submitting || state.status === "loading"}>
          {t("Sign in")}
        </Button>
      </form>
      <div className="mt-5 flex justify-center">
        <LanguageSwitch />
      </div>
    </Card>
  );
}
