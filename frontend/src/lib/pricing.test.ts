import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { NOT_TAXED, calculateTotals, refundShare, taxKeyFor, type PricingLine } from "./pricing";
import type { DiscountType } from "./types";

/**
 * The same cases the backend's PricingFixtureTest runs (expected values from an independent
 * reference calculation), so the cart shows exactly what the server charges.
 */
interface Case {
  name: string;
  lines: {
    unitPrice: string;
    quantity: number;
    hours: string | null;
    tax: { rateId: number; name: string; rate: string; included: boolean };
    discountType?: DiscountType;
    discountValue?: string;
  }[];
  discountType: DiscountType | null;
  discountValue: string | null;
  expected: Record<"subtotal" | "itemDiscount" | "discount" | "taxAdded" | "taxIncluded" | "total" | "taxableAmount" | "nonTaxableAmount", string> & {
    lines: Record<"total" | "itemDiscount" | "discount" | "net" | "tax" | "paid", string>[];
  };
}

const cases = JSON.parse(
  readFileSync(path.resolve(__dirname, "../../../backend/src/test/resources/pricing-cases.json"), "utf8"),
) as Case[];

describe("shared pricing cases", () => {
  it.each(cases.map((c) => [c.name, c] as const))("%s", (_, c) => {
    const lines: PricingLine[] = c.lines.map((l) => ({
      unitPrice: Number(l.unitPrice),
      quantity: l.quantity,
      hours: l.hours === null ? null : Number(l.hours),
      tax: { ...l.tax, rate: Number(l.tax.rate) },
      discountType: l.discountType ?? null,
      discountValue: l.discountValue != null ? Number(l.discountValue) : 0,
    }));
    const t = calculateTotals(lines, c.discountType, Number(c.discountValue ?? 0));
    const e = c.expected;
    expect(t.subtotal).toBe(Number(e.subtotal));
    expect(t.discount).toBe(Number(e.discount));
    expect(t.itemDiscount).toBe(Number(e.itemDiscount));
    expect(t.taxAdded).toBe(Number(e.taxAdded));
    expect(t.taxIncluded).toBe(Number(e.taxIncluded));
    expect(t.total).toBe(Number(e.total));
    expect(t.taxableAmount).toBe(Number(e.taxableAmount));
    expect(t.nonTaxableAmount).toBe(Number(e.nonTaxableAmount));
    t.lines.forEach((s, i) => {
      expect(s.discount).toBe(Number(e.lines[i].discount));
      expect(s.itemDiscount).toBe(Number(e.lines[i].itemDiscount));
      expect(s.net).toBe(Number(e.lines[i].net));
      expect(s.tax).toBe(Number(e.lines[i].tax));
      expect(s.paid).toBe(Number(e.lines[i].paid));
    });
  });
});

describe("taxKeyFor mirrors TaxService.keyFor", () => {
  const vat = { type: "PRODUCT" as const, taxRateId: 1, taxRateName: "Standard", taxRate: 0.1 };
  const store = { taxLabel: "VAT", defaultTaxRateId: 1, taxScope: ["PRODUCT", "SERVICE", "ITEM", "PACKAGE"] as const };

  it("charges nothing under No VAT", () => {
    expect(taxKeyFor(vat, { ...store, taxScope: [...store.taxScope], taxMode: "NONE" })).toEqual({ rateId: 1, name: NOT_TAXED, rate: 0, included: false });
  });
  it("includes the tax in VAT included mode, under the store label", () => {
    expect(taxKeyFor(vat, { ...store, taxScope: [...store.taxScope], taxMode: "INCLUSIVE" })).toEqual({ rateId: 1, name: "VAT", rate: 0.1, included: true });
  });
  it("adds the tax in VAT setup value mode", () => {
    expect(taxKeyFor(vat, { ...store, taxScope: [...store.taxScope], taxMode: "EXCLUSIVE" }).included).toBe(false);
  });
  it("skips types outside the scope and keeps exempt names", () => {
    expect(taxKeyFor({ ...vat, type: "SERVICE" }, { ...store, taxScope: ["PRODUCT"], taxMode: "EXCLUSIVE" }).rate).toBe(0);
    expect(taxKeyFor({ ...vat, taxRateId: 2, taxRateName: "Exempt", taxRate: 0 }, { ...store, taxScope: [...store.taxScope], taxMode: "INCLUSIVE" })).toEqual({
      rateId: 2,
      name: "Exempt",
      rate: 0,
      included: false,
    });
  });
});

describe("refundShare mirrors RefundSplit", () => {
  const line = { linePaid: 4.46, lineTax: 0.41, quantity: 3, refundedQty: 0, refundedAmount: 0, refundedTax: 0 };
  it("refunds a unit's share with its tax", () => {
    expect(refundShare(line, 1)).toEqual({ amount: 1.49, tax: 0.14 });
  });
  it("refunds exactly what is left on the last units", () => {
    expect(refundShare({ ...line, refundedQty: 1, refundedAmount: 1.49, refundedTax: 0.14 }, 2)).toEqual({ amount: 2.97, tax: 0.27 });
    expect(refundShare(line, 3)).toEqual({ amount: 4.46, tax: 0.41 });
  });
});
