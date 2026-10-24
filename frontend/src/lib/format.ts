import { dateLocale, getLang, msg, translate } from "./i18n";
import type { Currency } from "./types";

export function money(amount: number, currency = "USD") {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amount);
}

/** Formats an amount in a currency from the currencies list, e.g. "50,700 ៛" or "$12.35". */
export function amountIn(amount: number, c: Pick<Currency, "code" | "symbol" | "decimals">) {
  const n = new Intl.NumberFormat("en-US", { minimumFractionDigits: c.decimals, maximumFractionDigits: c.decimals }).format(amount);
  return c.code === "USD" ? `$${n}` : `${n} ${c.symbol}`;
}

/** Rounds to the nearest amount that can be handed over in cash, e.g. 50,696.75 riel to 50,700. */
export function roundCash(amount: number, c: Pick<Currency, "cashRounding" | "decimals">) {
  const steps = Math.round(amount / c.cashRounding + 1e-9);
  return Number((steps * c.cashRounding).toFixed(c.decimals));
}

/** A base amount in another currency at a rate, rounded for cash. */
export function cashIn(baseAmount: number, c: Currency, rate = c.rate ?? 1) {
  return roundCash(baseAmount * rate, c);
}

/** Khmer month names. Browsers without Khmer locale data would otherwise fall back to English. */
const KHMER_MONTHS = ["មករា", "កុម្ភៈ", "មីនា", "មេសា", "ឧសភា", "មិថុនា", "កក្កដា", "សីហា", "កញ្ញា", "តុលា", "វិច្ឆិកា", "ធ្នូ"];

/** Day, month (1-12), year, hour and minute of a moment in a time zone. */
function parts(date: Date, timeZone?: string) {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-GB", {
      timeZone,
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(date)
      .map((x) => [x.type, x.value]),
  );
  return { day: Number(p.day), month: Number(p.month), year: p.year, time: `${p.hour}:${p.minute}` };
}

/** "24 Oct 2026, 13:05", or in Khmer "24 តុលា 2026, 13:05". */
export function dateTime(iso: string, timeZone?: string) {
  const date = new Date(iso);
  if (getLang() === "km") {
    const x = parts(date, timeZone);
    return `${x.day} ${KHMER_MONTHS[x.month - 1]} ${x.year}, ${x.time}`;
  }
  return new Intl.DateTimeFormat(dateLocale(), { dateStyle: "medium", timeStyle: "short", timeZone }).format(date);
}

/** "18 Oct", or in Khmer "18 តុលា", for a yyyy-mm-dd date. */
export function shortDate(isoDate: string) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  if (getLang() === "km") {
    const x = parts(date, "UTC");
    return `${x.day} ${KHMER_MONTHS[x.month - 1]}`;
  }
  return new Intl.DateTimeFormat(dateLocale(), { day: "numeric", month: "short", timeZone: "UTC" }).format(date);
}

export function todayIn(timeZone: string) {
  return new Intl.DateTimeFormat("en-CA", { timeZone }).format(new Date());
}

/** English labels (marked for translation): show them with `t(TYPE_LABELS[type])`. */
export const TYPE_LABELS = { PRODUCT: msg("Product"), SERVICE: msg("Service"), ITEM: msg("Item"), PACKAGE: msg("Package") };

/** "2 pcs", "1.5 h", "3" */
export function qty(quantity: number, unit: string | null) {
  const n = Number(quantity.toFixed(3));
  return unit ? `${n} ${unit}` : `${n}`;
}

export function duration(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const [hh, mm] = [translate("h"), translate("min")];
  return h && m ? `${h} ${hh} ${m} ${mm}` : h ? `${h} ${hh}` : `${m} ${mm}`;
}

export function hours(value: number) {
  return `${Number(value.toFixed(2))} ${translate("h")}`;
}

/** English labels (marked for translation): show them with `t(MOVEMENT_LABELS[type])`. */
export const MOVEMENT_LABELS = {
  OPENING: msg("Opening stock"),
  SALE: msg("Sale"),
  SALE_RETURN: msg("Unpaid sale returned"),
  REFUND: msg("Refund"),
  PURCHASE: msg("Purchase received"),
  RECEIVE: msg("Received"),
  REMOVE: msg("Removed"),
  COUNT: msg("Stock count"),
  TRANSFER_OUT: msg("Transfer out"),
  TRANSFER_IN: msg("Transfer in"),
  TRANSFER_RETURN: msg("Transfer cancelled"),
  ADJUSTMENT_IN: msg("Adjustment (added)"),
  ADJUSTMENT_OUT: msg("Adjustment (removed)"),
};
