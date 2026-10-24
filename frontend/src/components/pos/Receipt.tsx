"use client";

import { amountIn, cashIn, dateTime, hours, money, qty } from "@/lib/format";
import { taxLabel } from "@/lib/pricing";
import { logoUrl, useCurrencies } from "@/lib/queries";
import type { Order, StoreSettings } from "@/lib/types";
import { useT } from "@/lib/i18n";

/**
 * A printable 80mm receipt. Wrap in .print-area to print only this. Tax lines follow the order's
 * own tax mode and label (as sold), not the current settings, so reprints never change.
 */
export function Receipt({ order, store }: { order: Order; store: StoreSettings }) {
  const t = useT();
  const vat = order.taxLabel;
  const exclusive = order.taxMode === "EXCLUSIVE";
  const paid = order.payments.find((p) => p.status === "PAID" || p.status === "REFUNDED");
  const currencies = useCurrencies().data ?? [];
  const find = (code: string | null) => currencies.find((c) => c.code === code);
  const show = (amount: number, code: string | null) => {
    const c = find(code);
    return c ? amountIn(amount, c) : `${amount} ${code ?? ""}`;
  };
  return (
    <div className="mx-auto w-full max-w-[320px] bg-white p-4 font-mono text-[12px] leading-relaxed text-black">
      <div className="text-center">
        {store.hasLogo && (
          // eslint-disable-next-line @next/next/no-img-element -- served by our API, sized for 80mm paper
          <img src={logoUrl(store.logoVersion)} alt="" className="mx-auto mb-1 max-h-16 max-w-[60%] object-contain" />
        )}
        <div className="text-sm font-bold">{store.name}</div>
        {store.legalName && <div>{store.legalName}</div>}
        {store.address && <div>{store.address}</div>}
        {store.phone && <div>{t("Tel")} {store.phone}</div>}
        {store.taxId && order.taxMode !== "NONE" && <div>{t("{vat} TIN", { vat })} {store.taxId}</div>}
        <div className="mt-1">{order.branchName}</div>
        <div>{t("Receipt {number}", { number: order.orderNumber })}</div>
        <div>{dateTime(order.completedAt ?? order.createdAt, store.timezone)}</div>
        <div>{t("Cashier: {name}", { name: order.cashierName })}</div>
        {order.walkIn ? (
          order.customerName && <div>{t("Customer: {name}", { name: order.customerName })}</div>
        ) : (
          <>
            <div>{t("Customer: {name}", { name: order.customerName })}</div>
            {order.customerPhone && <div>{t("Tel")} {order.customerPhone}</div>}
            {order.customerTaxId && <div>{t("Customer VAT TIN {tin}", { tin: order.customerTaxId })}</div>}
          </>
        )}
        {order.status === "REFUNDED" && <div className="mt-1 font-bold">*** {t("REFUNDED")} ***</div>}
        {order.status === "PARTIALLY_REFUNDED" && <div className="mt-1 font-bold">*** {t("PARTLY REFUNDED")} ***</div>}
      </div>
      <hr className="my-2 border-dashed border-black" />
      {order.items.map((item, i) => (
        <div key={i} className="mb-1">
          <div>{item.name}</div>
          {item.nameKm && <div lang="km">{item.nameKm}</div>}
          <div className="flex justify-between">
            <span>
              {item.quantity} × {money(item.unitPrice, order.currency)}
              {item.hours != null ? ` × ${hours(item.hours)}` : item.unit ? ` /${item.unit}` : ""}
              {item.taxRate === 0 && order.taxMode !== "NONE" ? ` (${item.taxName})` : ""}
            </span>
            <span>{money(item.lineTotal, order.currency)}</span>
          </div>
          {item.contents.map((c, j) => (
            <div key={j} style={{ paddingLeft: `${(c.depth + 1) * 8}px` }}>
              · {qty(c.quantity, c.unit)} {c.name}
              {c.nameKm && <span lang="km"> ({c.nameKm})</span>}
            </div>
          ))}
          {item.priceOverridden && <div>  {t("Price changed (was {price})", { price: money(item.listPrice, order.currency) })}</div>}
          {item.itemDiscount > 0 && (
            <div className="flex justify-between">
              <span>  {t("Discount")}{item.itemDiscountType === "PERCENT" ? ` ${item.itemDiscountValue}%` : ""}</span>
              <span>-{money(item.itemDiscount, order.currency)}</span>
            </div>
          )}
          {item.staffName && <div>  {t("By {name}", { name: item.staffName })}</div>}
        </div>
      ))}
      <hr className="my-2 border-dashed border-black" />
      <Row label={exclusive ? t("Subtotal (excl. {vat})", { vat }) : t("Subtotal")} value={money(order.subtotal, order.currency)} />
      {order.itemDiscount > 0 && <Row label={t("Item discounts")} value={`-${money(order.itemDiscount, order.currency)}`} />}
      {order.discountAmount > 0 && (
        <Row
          label={`${order.itemDiscount > 0 ? t("Order discount") : t("Discount")}${order.discountType === "PERCENT" ? ` ${order.discountValue}%` : ""}`}
          value={`-${money(order.discountAmount, order.currency)}`}
        />
      )}
      {exclusive && order.taxAmount > 0 && <Row label={t("Taxable amount")} value={money(order.taxableAmount, order.currency)} />}
      {order.taxes
        .filter((x) => !x.included && x.tax > 0)
        .map((x) => (
          <Row key={`${x.name}-added`} label={taxLabel(x)} value={money(x.tax, order.currency)} />
        ))}
      <div className="mt-1 flex justify-between text-sm font-bold">
        <span>{t("TOTAL")}</span>
        <span>{money(order.total, order.currency)}</span>
      </div>
      {/* The total in the other currencies, at the rates the order was sold at. */}
      {order.rates
        .filter((r) => r.currency !== order.currency)
        .map((r) => {
          const c = find(r.currency);
          return c ? <Row key={r.currency} label={`@ ${r.rate}`} value={amountIn(cashIn(order.total, c, r.rate), c)} /> : null;
        })}
      {order.taxIncluded > 0 && <Row label={t("Includes {vat}", { vat })} value={money(order.taxIncluded, order.currency)} />}
      {order.returns.length > 0 && (
        <>
          {order.returns.map((r) => (
            <Row key={r.id} label={t("Refund {number}", { number: r.returnNumber })} value={`-${money(r.total, order.currency)}`} />
          ))}
          <Row label={t("Net paid")} value={money(order.total - order.refundedTotal, order.currency)} />
        </>
      )}
      {order.taxMode !== "NONE" && order.taxes.some((x) => x.rate > 0) && (
        <>
          <div className="mt-2 text-[11px] font-bold">{t("Tax summary")}</div>
          {order.taxes
            .filter((x) => x.rate > 0)
            .map((x) => (
              <div key={`${x.name}-${x.included}`} className="flex justify-between text-[11px]">
                <span>
                  {x.included
                    ? t("{tax} incl. on {base}", { tax: taxLabel(x), base: money(x.base, order.currency) })
                    : t("{tax} added on {base}", { tax: taxLabel(x), base: money(x.base, order.currency) })}
                </span>
                <span>{money(x.tax, order.currency)}</span>
              </div>
            ))}
        </>
      )}
      {paid && (
        <>
          <hr className="my-2 border-dashed border-black" />
          {paid.method === "KHQR" ? (
            <Row label={t("Paid by KHQR")} value={show(paid.currencyAmount ?? paid.amount, paid.currency ?? order.currency)} />
          ) : (
            paid.tenders.map((x) => <Row key={x.currency} label={t("Cash {currency}", { currency: x.currency })} value={show(x.amount, x.currency)} />)
          )}
          {paid.changeAmount != null && paid.changeAmount > 0 && (
            <Row label={t("Change")} value={show(paid.changeAmount, paid.changeCurrency)} />
          )}
        </>
      )}
      <hr className="my-2 border-dashed border-black" />
      {store.receiptFooter && <div className="text-center whitespace-pre-line">{store.receiptFooter}</div>}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span>{label}</span>
      <span>{value}</span>
    </div>
  );
}
