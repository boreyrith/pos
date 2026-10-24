import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import { translate, translateServer } from "./i18n";
import { KM } from "./i18n/km";
import { KM_PATTERNS } from "./i18n/km-patterns";

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe("Khmer dictionary", () => {
  it("translates every text the screens use", () => {
    const script = fileURLToPath(new URL("../../scripts/i18n-check.mjs", import.meta.url));
    // Exits non-zero and lists what is missing.
    expect(() => execFileSync("node", [script], { encoding: "utf8" })).not.toThrow();
  });

  it("keeps every {placeholder} of the English", () => {
    const wrong = Object.entries(KM).filter(([en, km]) => placeholders(en).join() !== placeholders(km).join());
    expect(wrong).toEqual([]);
  });

  it("has no empty translations", () => {
    expect(Object.entries(KM).filter(([, km]) => !km.trim())).toEqual([]);
  });

  it("keeps the numbered parts of every server pattern", () => {
    for (const [pattern, km] of KM_PATTERNS) {
      const groups = new RegExp(`${pattern.source}|`).exec("")!.length - 1;
      const used = new Set([...km.matchAll(/\$(\d)/g)].map((m) => Number(m[1])));
      for (const n of used) expect(n, `${pattern} uses $${n}`).toBeLessThanOrEqual(groups);
    }
  });
});

describe("translate", () => {
  it("is English by default, with placeholders filled", () => {
    expect(translate("{n} items", { n: 3 })).toBe("3 items");
    expect(translate("Cash payment", undefined, "en")).toBe("Cash payment");
  });

  it("uses Khmer when chosen", () => {
    expect(translate("Cash payment", undefined, "km")).toBe("ការទូទាត់ជាសាច់ប្រាក់");
    expect(translate("{n} items", { n: 3 }, "km")).toBe("3 មុខ");
  });

  it("falls back to the English for unknown text", () => {
    expect(translate("Banana Bread", undefined, "km")).toBe("Banana Bread");
  });

  it("never shows a context marker", () => {
    expect(translate("Change||quantity", undefined, "en")).toBe("Change");
    expect(translate("Change||quantity", undefined, "km")).toBe("ការប្រែប្រួល");
  });
});

describe("server messages", () => {
  it("translates exact messages", () => {
    expect(translateServer("Wrong username or password", "km")).toBe("ឈ្មោះអ្នកប្រើ ឬពាក្យសម្ងាត់មិនត្រឹមត្រូវ");
  });

  it("translates messages with names and numbers", () => {
    expect(translateServer("Not enough stock of Banana Bread in Main Shop / Showroom (0 left, 1 needed)", "km")).toBe(
      "ស្តុក Banana Bread នៅ Main Shop / Showroom មិនគ្រប់គ្រាន់ (នៅសល់ 0 ត្រូវការ 1)",
    );
    expect(translateServer("This order is completed", "km")).toBe("បញ្ជាទិញនេះ បានបញ្ចប់");
    expect(translateServer("must not be blank", "km")).toBe("មិនអាចទទេបានទេ");
  });

  it("leaves English untouched, and unknown messages as they are", () => {
    expect(translateServer("Not enough stock of X in Y (0 left, 1 needed)", "en")).toBe("Not enough stock of X in Y (0 left, 1 needed)");
    expect(translateServer("Some brand-new server message", "km")).toBe("Some brand-new server message");
  });
});

describe("dates", () => {
  it("uses Khmer month names in Khmer, in the store's time zone", async () => {
    const { setLang } = await import("./i18n");
    const { dateTime, shortDate } = await import("./format");
    setLang("km");
    try {
      expect(dateTime("2026-10-23T06:05:00Z", "Asia/Phnom_Penh")).toBe("23 តុលា 2026, 13:05");
      expect(shortDate("2026-01-05")).toBe("5 មករា");
    } finally {
      setLang("en");
    }
    expect(shortDate("2026-01-05")).toBe("5 Jan");
  });
});
