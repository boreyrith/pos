"use client";

import { useCallback, useSyncExternalStore } from "react";

import { KM } from "./i18n/km";
import { KM_PATTERNS } from "./i18n/km-patterns";

/**
 * English (the default) and Khmer. The English text is the key: `t("Cash payment")` shows the Khmer
 * from `i18n/km.ts` when Khmer is chosen, and the English when there is no translation, so nothing
 * ever shows blank. Placeholders in braces are filled in: `t("{n} items", { n: 3 })`.
 *
 * The same English word with two meanings gets a context after "||", e.g. `t("Change||quantity")`
 * shows "Change" in English but has its own Khmer.
 *
 * Text kept outside components (tab lists, labels in constants) is marked with `msg("...")` so the
 * check (`npm run i18n:check`) finds it; it is translated where it is shown, with `t(label)`.
 */
export type Lang = "en" | "km";
export const LANGUAGES: { code: Lang; label: string; native: string }[] = [
  { code: "en", label: "English", native: "English" },
  { code: "km", label: "Khmer", native: "ខ្មែរ" },
];

const STORAGE_KEY = "pos.lang";
type Vars = Record<string, string | number | null | undefined>;

let current: Lang = "en";
const listeners = new Set<() => void>();

if (typeof window !== "undefined") {
  try {
    if (localStorage.getItem(STORAGE_KEY) === "km") current = "km";
  } catch {
    // storage blocked: English
  }
  document.documentElement.lang = current;
}

export function getLang(): Lang {
  return current;
}

/** Switches the language everywhere at once and remembers it on this device. */
export function setLang(lang: Lang) {
  if (lang === current) return;
  current = lang;
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    // not remembered on this device; the account keeps it
  }
  if (typeof document !== "undefined") document.documentElement.lang = lang;
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The current language; components using it re-render when it changes. */
export function useLang(): Lang {
  return useSyncExternalStore(subscribe, getLang, () => "en");
}

function fill(text: string, vars?: Vars) {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => (vars[name] == null ? whole : String(vars[name])));
}

/** Translates outside React (formatting helpers, error messages). */
export function translate(text: string, vars?: Vars, lang: Lang = current): string {
  const khmer = lang === "km" ? KM[text] : undefined;
  // "Change||quantity": the part after || tells same-looking English apart; it is never shown.
  return fill(khmer ?? text.split("||")[0], vars);
}

/** `t(text, vars)` in the current language. Missing text (an optional label) passes through. */
export type T = {
  (text: string, vars?: Vars): string;
  (text: string | null | undefined, vars?: Vars): string | undefined;
};

export function useT(): T {
  const lang = useLang();
  return useCallback(
    (text: string | null | undefined, vars?: Vars) => (text == null ? undefined : translate(text, vars, lang)),
    [lang],
  ) as T;
}

/** Marks text kept outside components for translation; returns it unchanged (English). */
export const msg = (text: string) => text;

/**
 * Translates a message that came from the server (errors, validation, report and permission names):
 * an exact match first, then patterns for messages with names and numbers in them.
 */
export function translateServer(text: string, lang?: Lang): string;
export function translateServer(text: string | null | undefined, lang?: Lang): string | null | undefined;
export function translateServer(text: string | null | undefined, lang: Lang = current): string | null | undefined {
  if (lang !== "km" || !text) return text;
  const exact = KM[text];
  if (exact) return exact;
  for (const [pattern, khmer] of KM_PATTERNS) {
    const m = pattern.exec(text);
    if (m) return khmer.replace(/\$(\d)/g, (_, i: string) => translateServer(m[Number(i)] ?? "", lang));
  }
  return text;
}

/** Formatting locale for dates and times. */
export function dateLocale(lang: Lang = current) {
  return lang === "km" ? "km-KH" : "en-GB";
}
