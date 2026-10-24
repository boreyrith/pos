"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { Badge, Button, ErrorNote, Field, Input, Modal, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import type { HeldSale } from "@/lib/types";
import { msg, translate, useT } from "@/lib/i18n";

export const heldSalesKey = ["held-sales"] as const;

/** "just now", "12 min ago", "3 h ago", "2 d ago" */
export function ago(iso: string, now = Date.now()) {
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000));
  if (min < 1) return translate("just now");
  if (min < 60) return translate("{n} min ago", { n: min });
  const h = Math.floor(min / 60);
  return h < 48 ? translate("{n} h ago", { n: h }) : translate("{n} d ago", { n: Math.floor(h / 24) });
}

/** "1 item", "3 items" */
export const itemCount = (n: number) => (n === 1 ? translate("{n} item", { n }) : translate("{n} items", { n }));

/** Who a hold is for, as shown in lists. */
export const holdTitle = (h: Pick<HeldSale, "label" | "customerName" | "holdNumber">) =>
  h.label ?? h.customerName ?? translate("Walk-in");

/** Asks for a label and note before holding the current cart. */
export function HoldDialog({
  open,
  defaultLabel,
  defaultNote,
  holdNumber,
  submitting,
  error,
  onClose,
  onHold,
}: {
  open: boolean;
  defaultLabel: string;
  defaultNote: string;
  /** Set when the cart was resumed from a hold: holding again updates it. */
  holdNumber: string | null;
  submitting: boolean;
  error: unknown;
  onClose: () => void;
  onHold: (label: string, note: string) => void;
}) {
  const t = useT();
  return (
    <Modal open={open} onClose={onClose} title={holdNumber ? t("Hold {number} again", { number: holdNumber }) : t("Hold this sale")}>
      {open && (
        <HoldForm
          defaultLabel={defaultLabel}
          defaultNote={defaultNote}
          submitting={submitting}
          error={error}
          onClose={onClose}
          onHold={onHold}
        />
      )}
    </Modal>
  );
}

function HoldForm({
  defaultLabel,
  defaultNote,
  submitting,
  error,
  onClose,
  onHold,
}: {
  defaultLabel: string;
  defaultNote: string;
  submitting: boolean;
  error: unknown;
  onClose: () => void;
  onHold: (label: string, note: string) => void;
}) {
  const t = useT();
  const [label, setLabel] = useState(defaultLabel);
  const [note, setNote] = useState(defaultNote);
  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        onHold(label.trim(), note.trim());
      }}
    >
      <p className="text-sm text-muted">
        {t("The sale is saved for this branch, so any till can finish it. Prices are checked again when it is paid.")}
      </p>
      <Field label={t("Name it")} hint={t("So it is easy to find, e.g. a table, a name or a phone number")}>
        <Input autoFocus value={label} maxLength={100} onChange={(e) => setLabel(e.target.value)} placeholder={t("Table 4")} />
      </Field>
      <Field label={t("Note (optional)")}>
        <Input value={note} maxLength={255} onChange={(e) => setNote(e.target.value)} placeholder={t("Coming back at 5pm")} />
      </Field>
      <ErrorNote error={error} />
      <div className="mt-1 grid grid-cols-2 gap-2">
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("Cancel")}
        </Button>
        <Button type="submit" loading={submitting}>
          {t("Hold sale")}
        </Button>
      </div>
    </form>
  );
}

const STATUS: Record<HeldSale["status"], { label: string; tone: "neutral" | "primary" | "success" | "warning" | "danger" }> = {
  HELD: { label: msg("Held"), tone: "primary" },
  IN_USE: { label: msg("In use"), tone: "warning" },
  COMPLETED: { label: msg("Paid"), tone: "success" },
  VOIDED: { label: msg("Voided"), tone: "danger" },
  EXPIRED: { label: msg("Expired"), tone: "neutral" },
};

/**
 * The branch's held sales. Resume one into a free cart; void your own (managers: anyone's); take
 * over one stuck at another till (managers).
 */
export function HeldSalesList({
  open,
  onClose,
  currency,
  canManage,
  onResume,
  resuming,
  resumeError,
}: {
  open: boolean;
  onClose: () => void;
  currency: string;
  canManage: boolean;
  onResume: (hold: HeldSale, takeOver: boolean) => void;
  resuming: number | null;
  resumeError: unknown;
}) {
  const t = useT();
  const [closed, setClosed] = useState(false);
  const [voiding, setVoiding] = useState<HeldSale | null>(null);
  const list = useQuery({
    queryKey: [...heldSalesKey, { closed }],
    queryFn: () => api<HeldSale[]>(`/held-sales${closed ? "?closed=true" : ""}`),
    enabled: open,
    refetchInterval: open ? 15_000 : false,
  });

  return (
    <Modal open={open} onClose={onClose} title={t("Held sales")} wide>
      <div className="mb-3 flex gap-1 rounded-lg border border-border p-1 text-sm" role="group" aria-label={t("Show")}>
        {[false, true].map((c) => (
          <button
            key={String(c)}
            type="button"
            aria-pressed={closed === c}
            onClick={() => setClosed(c)}
            className={cx("flex-1 rounded-md px-3 py-1.5", closed === c ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
          >
            {c ? t("Recently closed") : t("Waiting")}
          </button>
        ))}
      </div>
      <ErrorNote error={resumeError} />
      {list.isPending ? (
        <Spinner />
      ) : list.error ? (
        <ErrorNote error={list.error} />
      ) : list.data.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted">
          {closed ? t("Nothing closed recently.") : t("No held sales. Use Hold on a cart to park a sale and finish it later.")}
        </p>
      ) : (
        <ul className="max-h-[60vh] divide-y divide-border overflow-y-auto">
          {list.data.map((h) => {
            const elsewhere = h.status === "IN_USE" && !h.lockedByMe;
            const mine = h.createdByMe;
            return (
              <li key={h.id} className="flex flex-wrap items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs text-muted">{h.holdNumber}</span>
                    <span className="truncate font-medium">{holdTitle(h)}</span>
                    <Badge tone={STATUS[h.status].tone}>{t(STATUS[h.status].label)}</Badge>
                  </div>
                  <div className="text-xs text-muted">
                    {h.label && h.customerName ? `${h.customerName} · ` : ""}
                    {itemCount(h.itemCount)} · {t("held by {name} {when}", { name: h.createdBy, when: ago(h.createdAt) })}
                    {h.updatedBy && new Date(h.updatedAt).getTime() - new Date(h.createdAt).getTime() > 60_000
                      ? ` · ${t("changed by {name} {when}", { name: h.updatedBy, when: ago(h.updatedAt) })}`
                      : ""}
                  </div>
                  {h.note && <div className="truncate text-xs">“{h.note}”</div>}
                  {elsewhere && (
                    <div className="text-xs text-warning">
                      {h.orderId
                        ? t("Waiting for a KHQR payment at {name}'s till", { name: h.lockedBy })
                        : t("Open at {name}'s till", { name: h.lockedBy })}
                      {h.lockedAt ? ` · ${ago(h.lockedAt)}` : ""}
                    </div>
                  )}
                  {h.lockedByMe && h.status === "IN_USE" && <div className="text-xs text-primary">{t("Open in one of your carts")}</div>}
                  {h.status === "EXPIRED" && <div className="text-xs text-muted">{t("Expired {when}", { when: h.closedAt ? ago(h.closedAt) : "" })}</div>}
                  {h.status === "VOIDED" && <div className="text-xs text-danger">{t("Voided: {reason}", { reason: h.voidReason })}</div>}
                  {h.status === "COMPLETED" && h.orderId && (
                    <a href={`/orders/${h.orderId}`} className="text-xs text-primary hover:underline">
                      {t("See the order")}
                    </a>
                  )}
                </div>
                <div className="text-right font-semibold tabular-nums">
                  {money(h.estimatedTotal, currency)}
                  <div className="text-[11px] font-normal text-muted">{t("when held")}</div>
                </div>
                {h.status !== "COMPLETED" && h.status !== "VOIDED" && (
                  <div className="flex gap-1">
                    {(h.status === "HELD" || h.lockedByMe || (h.status === "EXPIRED" && canManage)) && (
                      <Button size="sm" loading={resuming === h.id} onClick={() => onResume(h, false)}>
                        {h.status === "EXPIRED" ? t("Restore") : h.lockedByMe ? t("Open") : t("Resume")}
                      </Button>
                    )}
                    {elsewhere && canManage && !h.orderId && (
                      <Button
                        size="sm"
                        variant="secondary"
                        loading={resuming === h.id}
                        onClick={() => {
                          if (confirm(t("Take {number} over from {name}? Their till can no longer finish it.", { number: h.holdNumber, name: h.lockedBy }))) onResume(h, true);
                        }}
                      >
                        {t("Take over")}
                      </Button>
                    )}
                    {(mine || canManage) && !h.orderId && (!elsewhere || canManage) && (
                      <Button size="sm" variant="ghost" className="text-danger" onClick={() => setVoiding(h)}>
                        {t("Void")}
                      </Button>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {!closed && (
        <p className="mt-3 text-xs text-muted">
          {t("Held sales expire after a day without being resumed. Totals are as they were when held; the cart shows today's prices.")}
        </p>
      )}
      {voiding && <VoidDialog hold={voiding} onClose={() => setVoiding(null)} onDone={() => list.refetch()} />}
    </Modal>
  );
}

function VoidDialog({ hold, onClose, onDone }: { hold: HeldSale; onClose: () => void; onDone: () => void }) {
  const t = useT();
  const [reason, setReason] = useState("");
  const voidHold = useMutation({
    mutationFn: () => api<HeldSale>(`/held-sales/${hold.id}/void`, { method: "POST", json: { reason: reason.trim() } }),
    onSuccess: () => {
      onDone();
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={t("Void {number}", { number: hold.holdNumber })}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          voidHold.mutate();
        }}
      >
        <p className="text-sm text-muted">
          {holdTitle(hold)} · {itemCount(hold.itemCount)}. {t("The sale is removed from the list; this is recorded in the audit log.")}
        </p>
        <Field label={t("Why?")}>
          <Input autoFocus value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} placeholder={t("Customer left")} />
        </Field>
        <ErrorNote error={voidHold.error} />
        <div className="grid grid-cols-2 gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Keep it")}
          </Button>
          <Button type="submit" variant="danger" disabled={reason.trim().length < 3} loading={voidHold.isPending}>
            {t("Void")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
