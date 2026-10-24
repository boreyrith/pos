"use client";

import { Languages } from "lucide-react";

import { useAuth } from "@/lib/auth";
import { LANGUAGES, type Lang, setLang, useLang, useT } from "@/lib/i18n";

import { cx } from "./ui";

/**
 * English / ខ្មែរ. Signed in, the choice is saved to the account (it follows the person to any
 * till); on the sign-in page it is remembered on this device and saved once they sign in.
 */
export function LanguageSwitch({ compact }: { compact?: boolean }) {
  const lang = useLang();
  const t = useT();
  const { state, changeLanguage } = useAuth();
  const choose = (next: Lang) => {
    if (next === lang) return;
    if (state.status === "signed-in") changeLanguage(next).catch(() => {});
    else setLang(next);
  };
  if (compact) {
    const next = lang === "en" ? "km" : "en";
    return (
      <button
        onClick={() => choose(next)}
        className="flex flex-col items-center gap-1 rounded-lg px-2 py-2 text-[11px] font-medium text-muted hover:bg-surface-2 hover:text-text"
        title={t("Language")}
        aria-label={t("Switch to {language}", { language: LANGUAGES.find((l) => l.code === next)!.native })}
      >
        <Languages className="size-5" aria-hidden />
        <span lang={next}>{LANGUAGES.find((l) => l.code === next)!.native}</span>
      </button>
    );
  }
  return (
    <div className="inline-flex overflow-hidden rounded-lg border border-border text-sm" role="group" aria-label={t("Language")}>
      {LANGUAGES.map((l) => (
        <button
          key={l.code}
          type="button"
          lang={l.code}
          aria-pressed={lang === l.code}
          onClick={() => choose(l.code)}
          className={cx("px-3 py-1.5", lang === l.code ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
        >
          {l.native}
        </button>
      ))}
    </div>
  );
}
