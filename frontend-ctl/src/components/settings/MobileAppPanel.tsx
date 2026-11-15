"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLink, Trash2, Upload } from "lucide-react";
import { useRef, useState } from "react";

import { Button, Card, ErrorNote, Field, Input, Spinner } from "@pos/shared/components/ui";
import { ApiError, api } from "@pos/shared/api/client";
import type { MobileAppInfo } from "@pos/shared/api/public";
import { hasPermission, useUser } from "@pos/shared/auth/auth";
import { dateTime } from "@pos/shared/utils/format";
import { ZONE_PATHS } from "@pos/shared/zones";
import { useT } from "@pos/shared/i18n";

interface MobileAppSettings {
  app: MobileAppInfo;
  apkDownloads: number;
  updatedBy: string | null;
}

const MAX_MB = 100;

/**
 * Settings > Mobile app: what the download page (/app) offers: the version and what's new, the Android
 * install file (APK, up to 100 MB) and links to Google Play, the App Store or TestFlight once published.
 */
export function MobileAppPanel() {
  const settings = useQuery({ queryKey: ["settings", "mobile-app"], queryFn: () => api<MobileAppSettings>("/settings/mobile-app") });
  if (settings.isPending) return <Spinner />;
  if (settings.error) return <ErrorNote error={settings.error} />;
  return <MobileAppForm key={settings.data.app.updatedAt} settings={settings.data} />;
}

function MobileAppForm({ settings }: { settings: MobileAppSettings }) {
  const t = useT();
  const queryClient = useQueryClient();
  const admin = hasPermission(useUser(), "SETTINGS_COMPANY");
  const app = settings.app;
  const [form, setForm] = useState({
    versionName: app.versionName ?? "",
    releaseNotes: app.releaseNotes ?? "",
    playStoreUrl: app.playStoreUrl ?? "",
    appStoreUrl: app.appStoreUrl ?? "",
    testflightUrl: app.testflightUrl ?? "",
  });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const fileRef = useRef<HTMLInputElement>(null);
  const [tooBig, setTooBig] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["settings", "mobile-app"] });
  const save = useMutation({
    mutationFn: () => api<MobileAppSettings>("/settings/mobile-app", { method: "PUT", json: form }),
    onSuccess: refresh,
  });
  const upload = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("file", file);
      return api<MobileAppSettings>("/settings/mobile-app/apk", { method: "PUT", body });
    },
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () => api<MobileAppSettings>("/settings/mobile-app/apk", { method: "DELETE" }),
    onSuccess: refresh,
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const field = (key: keyof typeof form, label: string, hint?: string, placeholder?: string) => (
    <Field label={t(label)} hint={hint ? t(hint) : undefined} error={errors[key]}>
      <Input value={form[key]} onChange={(e) => set(key)(e.target.value)} disabled={!admin} placeholder={placeholder} maxLength={255} />
    </Field>
  );

  return (
    <div className="flex flex-col gap-5">
      {!admin && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">{t("Your role can view these settings but not change them.")}</p>}
      <p className="text-sm text-muted">
        {t("Staff download the cashier app from the mobile app page.")}{" "}
        <a href={ZONE_PATHS.app} target="_blank" rel="noopener" className="inline-flex items-center gap-1 text-primary hover:underline">
          {t("Open the page")} <ExternalLink className="size-3.5" aria-hidden />
        </a>
      </p>

      <Card className="flex flex-col gap-4 p-5">
        <h2 className="text-sm font-semibold">{t("Android app (APK)")}</h2>
        {app.apk ? (
          <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
            <dt className="text-muted">{t("File")}</dt>
            <dd>{app.apk.fileName} · {(app.apk.sizeBytes / 1024 / 1024).toFixed(1)} MB</dd>
            <dt className="text-muted">{t("Uploaded")}</dt>
            <dd>{dateTime(app.apk.uploadedAt)}</dd>
            <dt className="text-muted">{t("Downloads")}</dt>
            <dd>{settings.apkDownloads}</dd>
            <dt className="text-muted">SHA-256</dt>
            <dd className="font-mono text-xs break-all">{app.apk.sha256}</dd>
          </dl>
        ) : (
          <p className="text-sm text-muted">{t("No APK uploaded: the page says Android isn't available yet (unless there is a Google Play link).")}</p>
        )}
        {admin && (
          <div className="flex flex-wrap items-center gap-2">
            <input
              ref={fileRef}
              type="file"
              accept=".apk,application/vnd.android.package-archive"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (!file) return;
                setTooBig(file.size > MAX_MB * 1024 * 1024);
                if (file.size <= MAX_MB * 1024 * 1024) upload.mutate(file);
              }}
            />
            <Button type="button" variant="secondary" onClick={() => fileRef.current?.click()} loading={upload.isPending}>
              <Upload className="size-4" aria-hidden /> {app.apk ? t("Upload a new version") : t("Upload APK")}
            </Button>
            {app.apk && (
              <Button type="button" variant="ghost" onClick={() => remove.mutate()} loading={remove.isPending}>
                <Trash2 className="size-4" aria-hidden /> {t("Remove")}
              </Button>
            )}
            <span className="text-xs text-muted">{t("The release build from mobile/ (flutter build apk --release), up to {mb} MB.", { mb: MAX_MB })}</span>
          </div>
        )}
        {tooBig && <p className="text-sm text-danger">{t("The APK must be 100 MB or smaller")}</p>}
        <ErrorNote error={upload.error ?? remove.error} />
      </Card>

      <form
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Card className="flex flex-col gap-4 p-5">
          <h2 className="text-sm font-semibold">{t("Version and what's new")}</h2>
          <div className="grid gap-4 sm:grid-cols-[200px_1fr]">
            {field("versionName", "Version", "Shown on the page and in the APK's file name", "1.0.0")}
            <Field label={t("What's new")} error={errors.releaseNotes}>
              <textarea
                value={form.releaseNotes}
                onChange={(e) => set("releaseNotes")(e.target.value)}
                maxLength={2000}
                rows={4}
                disabled={!admin}
                className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text focus:outline-2 focus:outline-primary"
              />
            </Field>
          </div>
        </Card>
        <Card className="flex flex-col gap-4 p-5">
          <h2 className="text-sm font-semibold">{t("Store links (optional)")}</h2>
          <p className="text-xs text-muted">{t("Once the app is published. iPhone and iPad can only install it from the App Store or TestFlight.")}</p>
          {field("playStoreUrl", "Google Play", undefined, "https://play.google.com/store/apps/details?id=…")}
          {field("appStoreUrl", "App Store", undefined, "https://apps.apple.com/…")}
          {field("testflightUrl", "TestFlight", undefined, "https://testflight.apple.com/join/…")}
        </Card>
        <ErrorNote error={save.error} />
        <div className="flex items-center justify-between gap-3">
          <span className="text-xs text-muted">
            {t("Last changed {updatedAt}", { updatedAt: dateTime(app.updatedAt) })}
            {settings.updatedBy ? t(" by {updatedBy}", { updatedBy: settings.updatedBy }) : ""}
          </span>
          {admin && (
            <Button type="submit" loading={save.isPending}>
              {t("Save settings")}
            </Button>
          )}
        </div>
      </form>
    </div>
  );
}
