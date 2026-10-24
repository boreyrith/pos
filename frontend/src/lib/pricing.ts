import type { DiscountType, OfferingType, TaxMode } from "./types";

/**
 * Mirrors PricingCalculator.java so the cart shows the same totals the server will charge. Works
 * in integer cents. A line costs listed price x quantity (x hours for hourly services); the listed
 * price includes its tax or excludes it. A line's own item discount (% or amount) comes off first;
 * the order discount is then worked out on what is left and shared over tax groups by value; then per group an included tax is taken out (amount x r / (1 + r))
 * and an added tax goes on top (amount x r). Each group's discount and tax are then shared over
 * its lines by value, the last line taking the leftover cent.
 * Both sides are checked against backend/src/test/resources/pricing-cases.json.
 */
export interface TaxKey {
  rateId: number;
  name: string;
  rate: number;
  included: boolean;
}

export interface PricingLine {
  unitPrice: number;
  quantity: number;
  /** Hours worked, for hourly services only. */
  hours?: number | null;
  tax: TaxKey;
  /** An item discount on this line. */
  discountType?: DiscountType | null;
  discountValue?: number;
}

export interface TaxLine extends TaxKey {
  /** Net amount after discount. */
  base: number;
  tax: number;
}

/** A line's share of the order: `discount` is its item discount plus its share of the order discount; `net` is after both without tax; `paid` = net + tax. */
export interface LineSplit {
  total: number;
  itemDiscount: number;
  discount: number;
  net: number;
  tax: number;
  paid: number;
}

export interface PricingTotals {
  /** Before any discount. */
  subtotal: number;
  /** Item discounts on all lines. */
  itemDiscount: number;
  /** The order discount, on the subtotal after item discounts. */
  discount: number;
  taxAdded: number;
  taxIncluded: number;
  total: number;
  taxes: TaxLine[];
  /** Per input line, same order. */
  lines: LineSplit[];
  /** Net amounts (after discount, before tax) of taxed and untaxed lines. */
  taxableAmount: number;
  nonTaxableAmount: number;
}

/** What lines outside the tax (No VAT, or a type not in scope) are labelled. Same as TaxService.NOT_TAXED. */
export const NOT_TAXED = "Not taxed";

/**
 * The tax a catalog entry is charged under. Mirrors TaxService.keyFor: nothing under No VAT or for
 * types outside the scope; the default rate is named after the store's tax label; an exempt (0%)
 * rate keeps its name; included only in VAT included mode.
 */
export function taxKeyFor(
  o: { type: OfferingType; taxRateId: number; taxRateName: string; taxRate: number },
  tax: { taxMode: TaxMode; taxScope: OfferingType[]; taxLabel: string; defaultTaxRateId: number | null },
): TaxKey {
  if (tax.taxMode === "NONE" || !tax.taxScope.includes(o.type)) {
    return { rateId: o.taxRateId, name: NOT_TAXED, rate: 0, included: false };
  }
  const taxed = o.taxRate > 0;
  const name = o.taxRateId === tax.defaultTaxRateId ? tax.taxLabel : o.taxRateName;
  return { rateId: o.taxRateId, name, rate: taxed ? o.taxRate : 0, included: taxed && tax.taxMode === "INCLUSIVE" };
}

const keyId = (k: TaxKey) => `${k.rateId}\u0000${k.name}\u0000${k.rate}\u0000${k.included}`;

const toCents = (amount: number) => Math.round(amount * 100);

/** Half-up rounding for non-negative values (Math.round is half-up for positives). */
const roundCents = (cents: number) => Math.round(cents + 1e-9);

const lineCents = (line: Pick<PricingLine, "unitPrice" | "quantity" | "hours">) =>
  roundCents(toCents(line.unitPrice) * line.quantity * (line.hours ?? 1));

export function lineTotal(line: Pick<PricingLine, "unitPrice" | "quantity" | "hours">) {
  return lineCents(line) / 100;
}

/** Cents taken off `cents` by a % or amount discount (an amount is capped at the whole). */
function discountCents(cents: number, type: DiscountType | null | undefined, value: number | undefined) {
  if (!type || !value || value <= 0) return 0;
  return type === "PERCENT" ? roundCents((cents * Math.min(value, 100)) / 100) : Math.min(toCents(value), cents);
}

/** The money an item discount takes off a line. */
export function itemDiscountOf(line: PricingLine) {
  return discountCents(lineCents(line), line.discountType, line.discountValue) / 100;
}

export function calculateTotals(lines: PricingLine[], discountType: DiscountType | null, discountValue: number): PricingTotals {
  // Grouped like Java's TaxKey equality: same rate id, name, rate and included.
  const groups = new Map<string, { key: TaxKey; gross: number; members: number[] }>();
  const gross = lines.map(lineCents);
  const itemOff = lines.map((l, i) => discountCents(gross[i], l.discountType, l.discountValue));
  // What the order discount and tax work from: each line after its item discount.
  const cents = gross.map((g, i) => g - itemOff[i]);
  const grossSubtotal = gross.reduce((n, c) => n + c, 0);
  const itemDiscount = itemOff.reduce((n, c) => n + c, 0);
  let subtotal = 0;
  lines.forEach((line, i) => {
    subtotal += cents[i];
    const id = keyId(line.tax);
    const g = groups.get(id) ?? { key: line.tax, gross: 0, members: [] };
    g.gross += cents[i];
    g.members.push(i);
    groups.set(id, g);
  });

  const discount = discountCents(subtotal, discountType, discountValue);

  const taxes: TaxLine[] = [];
  const splits: LineSplit[] = new Array(lines.length);
  let taxable = 0;
  let nonTaxable = 0;
  let added = 0;
  let included = 0;
  let left = discount;
  const all = [...groups.values()];
  all.forEach((g, i) => {
    const share = i === all.length - 1 ? left : subtotal === 0 ? 0 : roundCents((discount * g.gross) / subtotal);
    left -= share;
    const amount = g.gross - share;
    let tax: number;
    let base: number;
    if (g.key.included) {
      tax = roundCents((amount * g.key.rate) / (1 + g.key.rate));
      base = amount - tax;
      included += tax;
    } else {
      base = amount;
      tax = roundCents(amount * g.key.rate);
      added += tax;
    }
    taxes.push({ ...g.key, base: base / 100, tax: tax / 100 });
    if (g.key.rate > 0) taxable += base;
    else nonTaxable += base;

    let dLeft = share;
    let tLeft = tax;
    g.members.forEach((j, n) => {
      const t = cents[j];
      const last = n === g.members.length - 1;
      const d = last ? dLeft : g.gross === 0 ? 0 : roundCents((share * t) / g.gross);
      const lt = last ? tLeft : g.gross === 0 ? 0 : roundCents((tax * t) / g.gross);
      dLeft -= d;
      tLeft -= lt;
      const net = g.key.included ? t - d - lt : t - d;
      splits[j] = {
        total: gross[j] / 100,
        itemDiscount: itemOff[j] / 100,
        discount: (itemOff[j] + d) / 100,
        net: net / 100,
        tax: lt / 100,
        paid: (net + lt) / 100,
      };
    });
  });

  return {
    subtotal: grossSubtotal / 100,
    itemDiscount: itemDiscount / 100,
    discount: discount / 100,
    taxAdded: added / 100,
    taxIncluded: included / 100,
    total: (subtotal - discount + added) / 100,
    taxes,
    lines: splits,
    taxableAmount: taxable / 100,
    nonTaxableAmount: nonTaxable / 100,
  };
}

/** "VAT 10%" */
export const taxLabel = (t: { name: string; rate: number }) =>
  t.rate > 0 && !t.name.includes("%") ? `${t.name} ${Number((t.rate * 100).toFixed(2))}%` : t.name;

/**
 * What returning `qty` units of a sold line refunds, tax included, and the tax inside it. Mirrors
 * RefundSplit.java: the amount for units 1..n is round(paid x n / quantity), so returns add up the
 * same however they are split, and the line's last units refund exactly what is left.
 */
export function refundShare(
  line: { linePaid: number; lineTax: number; quantity: number; refundedQty: number; refundedAmount: number; refundedTax?: number },
  qty: number,
): { amount: number; tax: number } {
  const paid = toCents(line.linePaid);
  const tax = toCents(line.lineTax);
  const doneAmount = toCents(line.refundedAmount);
  // Tax already refunded follows the same cumulative rule, so it can be recomputed exactly.
  const doneTax = line.refundedTax != null ? toCents(line.refundedTax) : roundCents((tax * line.refundedQty) / line.quantity);
  if (qty <= 0) return { amount: 0, tax: 0 };
  if (line.refundedQty + qty >= line.quantity) return { amount: (paid - doneAmount) / 100, tax: (tax - doneTax) / 100 };
  const n = line.refundedQty + qty;
  return {
    amount: (roundCents((paid * n) / line.quantity) - doneAmount) / 100,
    tax: (roundCents((tax * n) / line.quantity) - doneTax) / 100,
  };
}
