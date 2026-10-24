"use client";

import { msg, useT } from "@/lib/i18n";
import type { OrderStatus, PaymentStatus } from "@/lib/types";

import { Badge } from "./ui";

const labels: Record<OrderStatus | PaymentStatus, string> = {
  PENDING_PAYMENT: msg("Awaiting payment"),
  PENDING: msg("Pending"),
  COMPLETED: msg("Completed"),
  PAID: msg("Paid"),
  CANCELLED: msg("Cancelled"),
  EXPIRED: msg("Expired"),
  PARTIALLY_REFUNDED: msg("Partly refunded"),
  REFUNDED: msg("Refunded"),
};

const tones = {
  PENDING_PAYMENT: "warning",
  PENDING: "warning",
  COMPLETED: "success",
  PAID: "success",
  CANCELLED: "neutral",
  EXPIRED: "neutral",
  PARTIALLY_REFUNDED: "warning",
  REFUNDED: "danger",
} as const;

export function StatusBadge({ status }: { status: OrderStatus | PaymentStatus }) {
  const t = useT();
  return <Badge tone={tones[status]}>{t(labels[status])}</Badge>;
}
