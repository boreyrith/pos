"use client";

import { useQuery } from "@tanstack/react-query";
import { Plus, UserRound, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Input, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import type { Customer, Page } from "@/lib/types";

import { CustomerDialog } from "./CustomerDialog";
import { useT } from "@/lib/i18n";

/** The part of a customer a held cart keeps. */
export type PickedCustomer = Pick<Customer, "id" | "code" | "name" | "phone">;

/**
 * Picks who a sale is for: search by name, phone or code; nobody picked means the Walk-in Customer.
 * People who may manage customers can add one on the spot.
 */
export function CustomerPicker({
  value,
  onChange,
  label,
}: {
  value: PickedCustomer | null;
  onChange: (c: PickedCustomer | null) => void;
  label: string;
}) {
  const can = useCan();
  const t = useT();
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const term = q.trim();
  const results = useQuery({
    queryKey: ["customers", "pick", term],
    queryFn: () => api<Page<Customer>>(`/customers${queryString({ q: term, activeOnly: true, size: 8 })}`),
    enabled: open && term.length > 0,
    placeholderData: (previous) => previous,
  });

  // Close the list when clicking elsewhere.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener("pointerdown", onDown);
    return () => window.removeEventListener("pointerdown", onDown);
  }, [open]);

  const pick = (c: Customer) => {
    onChange(c.walkIn ? null : { id: c.id, code: c.code, name: c.name, phone: c.phone });
    setQ("");
    setOpen(false);
  };

  if (value) {
    return (
      <div className="flex h-9 min-w-0 flex-1 items-center gap-2 rounded-lg border border-primary bg-primary-soft px-2.5 text-sm">
        <UserRound className="size-4 shrink-0 text-primary" aria-hidden />
        <span className="min-w-0 flex-1 truncate">
          <span className="font-medium">{value.name}</span>
          {value.phone && <span className="text-xs text-muted"> · {value.phone}</span>}
        </span>
        <button type="button" onClick={() => onChange(null)} aria-label={t("Back to Walk-in Customer")} className="rounded p-0.5 hover:bg-surface-2">
          <X className="size-4" />
        </button>
      </div>
    );
  }

  return (
    <div ref={box} className="relative min-w-0 flex-1">
      <Input
        aria-label={label}
        placeholder={t("Walk-in Customer · search name or phone")}
        value={q}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") setOpen(false);
          if (e.key === "Enter" && results.data?.items.length === 1) {
            e.preventDefault();
            pick(results.data.items[0]);
          }
        }}
        className="h-9 text-sm"
      />
      {open && (term.length > 0 || can("CUSTOMERS_MANAGE")) && (
        <ul className="absolute z-20 mt-1 max-h-72 w-full overflow-y-auto rounded-lg border border-border bg-surface text-sm shadow-lg">
          {term.length > 0 && results.data?.items.length === 0 && <li className="px-3 py-2 text-muted">{t("No customer matches “{term}”", { term })}</li>}
          {term.length > 0 &&
            results.data?.items.map((c) => (
              <li key={c.id}>
                <button type="button" onClick={() => pick(c)} className={cx("flex w-full justify-between gap-3 px-3 py-2 text-left hover:bg-surface-2")}>
                  <span className="min-w-0 truncate">
                    <span className="font-medium">{c.walkIn ? t("Walk-in Customer") : c.name}</span>
                    {c.phone && <span className="text-muted"> · {c.phone}</span>}
                  </span>
                  <span className="shrink-0 font-mono text-xs text-muted">{c.code}</span>
                </button>
              </li>
            ))}
          {can("CUSTOMERS_MANAGE") && (
            <li className="border-t border-border">
              <button type="button" onClick={() => setAdding(true)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-primary hover:bg-surface-2">
                <Plus className="size-4" aria-hidden /> {t("New customer")}{term ? ` “${term}”` : ""}
              </button>
            </li>
          )}
        </ul>
      )}
      {adding && (
        <CustomerDialog
          initialName={term}
          onClose={() => setAdding(false)}
          onSaved={(c) => {
            pick(c);
          }}
        />
      )}
    </div>
  );
}
