"use client";

import { Languages } from "lucide-react";
import { useId } from "react";

import { useAuth } from "@/lib/auth";
import { LANGUAGES, type Lang, setLang, useLang, useT } from "@/lib/i18n";

import { cx } from "./ui";

/**
 * English / ខ្មែរ. Signed in, the choice is saved to the account (it follows the person to any
 * till); on the sign-in page it is remembered on this device and saved once they sign in.
 */
export function LanguageSwitch({ compact, segmented }: { compact?: boolean; segmented?: boolean }) {
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
  if (segmented) {
    // An Apple-style segmented control of flags: the chosen one sits on a raised pill, the other is dimmed.
    return (
      <div className="inline-flex gap-1 rounded-full bg-surface-2/80 p-1 ring-1 ring-border backdrop-blur" role="group" aria-label={t("Language")}>
        {LANGUAGES.map((l) => (
          <button
            key={l.code}
            type="button"
            lang={l.code}
            aria-pressed={lang === l.code}
            aria-label={l.native}
            title={l.native}
            onClick={() => choose(l.code)}
            className={cx(
              "grid h-9 w-12 place-items-center rounded-full transition-[background-color,box-shadow,opacity,filter] duration-200",
              "focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary",
              lang === l.code ? "bg-surface shadow-sm ring-1 ring-border/60" : "opacity-55 grayscale-[40%] hover:opacity-100 hover:grayscale-0",
            )}
          >
            <Flag lang={l.code} />
          </button>
        ))}
      </div>
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

/**
 * A small flag for each language: the UK for English, Cambodia for Khmer. Drawn as SVG because
 * Windows shows emoji flags as letters ("GB", "KH").
 */
function Flag({ lang }: { lang: Lang }) {
  const id = useId();
  return (
    <svg viewBox="0 0 30 20" className="h-[18px] w-[27px] overflow-hidden rounded-[4px] shadow-[0_0_0_0.5px_rgb(0_0_0/0.15)]" aria-hidden>
      {lang === "km" ? (
        <>
          <rect width="30" height="20" fill="#032EA1" />
          <rect y="5" width="30" height="10" fill="#E00025" />
          {/* Angkor Wat, simplified: five towers on a gallery and a stepped base. */}
          <path
            fill="#fff"
            d="M15 5.6l.7 1.4v1.8l.5.4v1.4h-2.4V9.2l.5-.4V7zM11.6 7.6l.5 1v1.2l.4.3v.8h-1.8v-.8l.4-.3V8.6zM18.4 7.6l.5 1v1.2l.4.3v.8h-1.8v-.8l.4-.3V8.6zM9.4 8.8l.4.8v.9l.3.3v.6H8.7v-.6l.3-.3v-.9zM20.6 8.8l.4.8v.9l.3.3v.6h-1.4v-.6l.3-.3v-.9zM8.4 11.4h13.2v1.4H8.4zM7.6 12.8h14.8v.9H7.6zM6.8 13.7h16.4v.7H6.8z"
          />
        </>
      ) : (
        <>
          <clipPath id={`${id}-t`}>
            <path d="M15 10h15v10zv10H0zH0V0zV0h15z" />
          </clipPath>
          <rect width="30" height="20" fill="#012169" />
          <path d="M0 0l30 20M30 0L0 20" stroke="#fff" strokeWidth="4" />
          <path d="M0 0l30 20M30 0L0 20" stroke="#C8102E" strokeWidth="2.4" clipPath={`url(#${id}-t)`} />
          <path d="M15 0v20M0 10h30" stroke="#fff" strokeWidth="6.6" />
          <path d="M15 0v20M0 10h30" stroke="#C8102E" strokeWidth="4" />
        </>
      )}
    </svg>
  );
}
