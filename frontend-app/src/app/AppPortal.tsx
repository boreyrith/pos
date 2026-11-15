"use client";

import { Apple, Download, ShieldCheck, Smartphone } from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import { useSyncExternalStore } from "react";

import { CopyBox } from "@pos/shared/components/public/CopyBox";
import { PublicHeader } from "@pos/shared/components/public/PublicHeader";
import { Badge, Card, ErrorNote, Spinner, cx } from "@pos/shared/components/ui";
import { useMobileApp, usePublicStore, type MobileAppInfo } from "@pos/shared/api/public";
import { dateTime } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";
import { BASE_PATH } from "@pos/shared/zones";

type Platform = "android" | "ios" | "other";

const noSubscribe = () => () => {};
function detect(): Platform {
  const ua = navigator.userAgent;
  if (/Android/i.test(ua)) return "android";
  // iPadOS reports itself as a Mac with a touch screen.
  if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "ios";
  return "other";
}

const APK_URL = "/api/mobile-app/apk";

/**
 * The mobile cashier app: where to get it for Android and iPhone/iPad, how to install it, what to enter
 * as the server, and what's new. Everything comes from Settings > Mobile app (MobileAppService).
 */
export function AppPortal() {
  const t = useT();
  const app = useMobileApp();
  const store = usePublicStore().data;
  const platform = useSyncExternalStore(noSubscribe, detect, () => "other" as Platform);
  const host = useSyncExternalStore(noSubscribe, () => window.location.host, () => "");

  return (
    <>
      <PublicHeader name={store?.name ?? ""} />
      <main className="mx-auto max-w-5xl px-4 pb-16">
        {app.isPending ? (
          <Spinner />
        ) : app.error ? (
          <ErrorNote error={app.error} />
        ) : (
          <>
            <section className="flex flex-col items-center gap-4 py-10 text-center sm:py-14">
              {/* eslint-disable-next-line @next/next/no-img-element -- this app's own file */}
              <img src={`${BASE_PATH}/app-icon.svg`} alt="" className="size-24 rounded-[28px] shadow-xl ring-1 ring-border" />
              <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">{t("POS for Android and iPhone")}</h1>
              <p className="max-w-xl text-muted">{t("Sell, take cash and KHQR payments, hold sales and print receipts from a phone or tablet.")}</p>
              {app.data.versionName && (
                <p className="text-sm text-muted">
                  {t("Version {version}", { version: app.data.versionName })} · {t("updated {date}", { date: dateTime(app.data.updatedAt) })}
                </p>
              )}
            </section>

            <div className={cx("grid gap-4", "md:grid-cols-2")}>
              <AndroidCard app={app.data} highlight={platform === "android"} showQr={platform === "other"} />
              <IosCard app={app.data} highlight={platform === "ios"} />
            </div>

            <Card className="mt-6 flex flex-col gap-3 p-6">
              <h2 className="text-lg font-semibold">{t("After installing")}</h2>
              <ol className="ml-5 list-decimal space-y-1.5 text-sm text-muted">
                <li>{t("Open POS. On the sign-in screen, set the server to this address:")}</li>
              </ol>
              <CopyBox value={host} />
              <ol start={2} className="ml-5 list-decimal space-y-1.5 text-sm text-muted">
                <li>{t("Sign in with your staff username and password (the same as on the till).")}</li>
                <li>{t("Ask your manager if you don't have an account yet.")}</li>
              </ol>
            </Card>

            <div className="mt-6 grid gap-4 md:grid-cols-2">
              {app.data.releaseNotes && (
                <Card className="p-6">
                  <h2 className="mb-2 text-lg font-semibold">{t("What's new")}</h2>
                  <p className="text-sm whitespace-pre-line text-muted">{app.data.releaseNotes}</p>
                </Card>
              )}
              <Card className="p-6">
                <h2 className="mb-2 text-lg font-semibold">{t("Requirements")}</h2>
                <ul className="space-y-1.5 text-sm text-muted">
                  <li>{t("Android {version} or later", { version: app.data.androidMinimum })}</li>
                  <li>{t("iPhone and iPad with iOS {version} or later", { version: app.data.iosMinimum })}</li>
                  <li>{t("A connection to the shop's server (Wi-Fi or mobile data)")}</li>
                </ul>
              </Card>
            </div>
          </>
        )}
      </main>
    </>
  );
}

function PlatformCard({ icon: Icon, title, highlight, children }: { icon: typeof Apple; title: string; highlight: boolean; children: React.ReactNode }) {
  const t = useT();
  return (
    <Card className={cx("flex flex-col gap-4 p-6", highlight && "ring-2 ring-primary")}>
      <div className="flex items-center gap-3">
        <span className="grid size-11 place-items-center rounded-2xl bg-primary-soft text-primary">
          <Icon className="size-6" aria-hidden />
        </span>
        <h2 className="flex-1 text-lg font-semibold">{title}</h2>
        {highlight && <Badge tone="primary">{t("This device")}</Badge>}
      </div>
      {children}
    </Card>
  );
}

function AndroidCard({ app, highlight, showQr }: { app: MobileAppInfo; highlight: boolean; showQr: boolean }) {
  const t = useT();
  const page = useSyncExternalStore(noSubscribe, () => `${window.location.origin}${BASE_PATH}`, () => "");
  return (
    <PlatformCard icon={Smartphone} title={t("Android phones and tablets")} highlight={highlight}>
      {!app.apk && !app.playStoreUrl ? (
        <p className="text-sm text-muted">{t("Not available for Android yet. Check again soon, or ask your manager.")}</p>
      ) : (
        <>
          <div className="flex flex-wrap gap-2">
            {app.apk && (
              <a href={APK_URL} download={app.apk.fileName}
                className="inline-flex h-11 items-center gap-2 rounded-xl bg-primary px-4 font-medium text-on-primary hover:bg-primary-hover">
                <Download className="size-5" aria-hidden />
                {t("Download APK")} <span className="text-sm font-normal opacity-80">({(app.apk.sizeBytes / 1024 / 1024).toFixed(1)} MB)</span>
              </a>
            )}
            {app.playStoreUrl && (
              <a href={app.playStoreUrl} target="_blank" rel="noopener noreferrer"
                className="inline-flex h-11 items-center gap-2 rounded-xl border border-border px-4 font-medium hover:bg-surface-2">
                {t("Get it on Google Play")}
              </a>
            )}
          </div>
          {app.apk && showQr && page && (
            <div className="flex items-center gap-4 rounded-2xl bg-surface-2 p-3">
              <div className="rounded-xl bg-white p-2">
                <QRCodeSVG value={page} size={96} level="M" marginSize={1} />
              </div>
              <p className="text-sm text-muted">{t("On a computer? Scan this with your Android phone to open this page there.")}</p>
            </div>
          )}
          {app.apk && (
            <>
              <ol className="ml-5 list-decimal space-y-1.5 text-sm text-muted">
                <li>{t("Tap Download APK and open the file when it has downloaded.")}</li>
                <li>{t("If Android asks, allow your browser to install unknown apps (Settings opens; switch it on, then go back).")}</li>
                <li>{t("Tap Install, then Open.")}</li>
                <li>{t("To update later, download and install the new version the same way; your settings are kept.")}</li>
              </ol>
              <details className="text-xs text-muted">
                <summary className="flex cursor-pointer items-center gap-1.5">
                  <ShieldCheck className="size-4" aria-hidden />
                  {t("Check the file (SHA-256)")}
                </summary>
                <p className="mt-2 break-all font-mono">{app.apk.sha256}</p>
                <p className="mt-1">{t("Uploaded {date}", { date: dateTime(app.apk.uploadedAt) })}</p>
              </details>
            </>
          )}
        </>
      )}
    </PlatformCard>
  );
}

function IosCard({ app, highlight }: { app: MobileAppInfo; highlight: boolean }) {
  const t = useT();
  return (
    <PlatformCard icon={Apple} title={t("iPhone and iPad")} highlight={highlight}>
      {app.appStoreUrl ? (
        <>
          <a href={app.appStoreUrl} target="_blank" rel="noopener noreferrer"
            className="inline-flex h-11 w-fit items-center gap-2 rounded-xl bg-primary px-4 font-medium text-on-primary hover:bg-primary-hover">
            <Apple className="size-5" aria-hidden />
            {t("Download on the App Store")}
          </a>
          <p className="text-sm text-muted">{t("Install it like any app from the App Store.")}</p>
        </>
      ) : app.testflightUrl ? (
        <>
          <a href={app.testflightUrl} target="_blank" rel="noopener noreferrer"
            className="inline-flex h-11 w-fit items-center gap-2 rounded-xl bg-primary px-4 font-medium text-on-primary hover:bg-primary-hover">
            <Apple className="size-5" aria-hidden />
            {t("Join the test on TestFlight")}
          </a>
          <ol className="ml-5 list-decimal space-y-1.5 text-sm text-muted">
            <li>{t("Install Apple's TestFlight app from the App Store.")}</li>
            <li>{t("Open the TestFlight link above on your iPhone or iPad and tap Accept.")}</li>
            <li>{t("Tap Install. TestFlight lets you know when there is a new version.")}</li>
          </ol>
        </>
      ) : (
        <p className="text-sm text-muted">{t("Not available for iPhone and iPad yet. Check again soon, or ask your manager.")}</p>
      )}
    </PlatformCard>
  );
}
