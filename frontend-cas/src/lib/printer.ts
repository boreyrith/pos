"use client";

import { useSyncExternalStore } from "react";

/**
 * This till's receipt printing, kept in this browser (each till has its own printer, as in the mobile
 * app). The paper width is the store's (Settings > Company). Printing (printSheet) goes through the browser's print
 * dialog to the printer installed on this computer; Chrome started with --kiosk-printing skips the
 * dialog and prints straight to the default printer.
 */
export interface PrinterSettings {
  /** Print the receipt as soon as a sale is paid. */
  autoPrint: boolean;
  /** Receipts printed for a sale (e.g. 2: the customer's and the store's); reprints from Sales print one. */
  copies: number;
}

export const MAX_COPIES = 3;
const KEY = "pos.cas.printer";
const DEFAULTS: PrinterSettings = { autoPrint: false, copies: 1 };
let cached: PrinterSettings | null = null;
const listeners = new Set<() => void>();

function read(): PrinterSettings {
  if (cached) return cached;
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? "{}") as Partial<PrinterSettings>;
    cached = {
      autoPrint: saved.autoPrint === true,
      copies: Math.min(Math.max(Math.round(Number(saved.copies) || 1), 1), MAX_COPIES),
    };
  } catch {
    cached = DEFAULTS;
  }
  return cached;
}

export function savePrinter(settings: PrinterSettings) {
  cached = settings;
  try {
    localStorage.setItem(KEY, JSON.stringify(settings));
  } catch {
    // Private windows: kept until the page is closed.
  }
  listeners.forEach((l) => l());
}

export function usePrinter(): PrinterSettings {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      const onStorage = (e: StorageEvent) => {
        if (e.key === KEY) {
          cached = null;
          l();
        }
      };
      window.addEventListener("storage", onStorage);
      return () => {
        listeners.delete(l);
        window.removeEventListener("storage", onStorage);
      };
    },
    read,
    () => DEFAULTS,
  );
}

/**
 * Prints a PrintSheet (components/pos/Receipt) and nothing else, once its pictures (the logo) have
 * loaded so they are on the paper.
 */
export async function printSheet(id: string) {
  const sheet = document.querySelector<HTMLElement>(`.print-sheet[data-sheet="${CSS.escape(id)}"]`);
  if (!sheet) return;
  const images = [...sheet.querySelectorAll<HTMLImageElement>("img")];
  await Promise.all(
    images.map((img) =>
      img.complete
        ? null
        : new Promise((done) => {
            img.addEventListener("load", done, { once: true });
            img.addEventListener("error", done, { once: true });
            setTimeout(done, 3000);
          }),
    ),
  );
  document.querySelectorAll(".print-sheet.active").forEach((s) => s.classList.remove("active"));
  sheet.classList.add("active");
  window.addEventListener("afterprint", () => sheet.classList.remove("active"), { once: true });
  // Let the browser lay the sheet out before it is printed.
  await new Promise((r) => requestAnimationFrame(() => r(null)));
  window.print();
}
