"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Trash2, X } from "lucide-react";
import { useState } from "react";

import { Button, ErrorNote, Input, Modal, Spinner } from "@/components/ui";
import { api } from "@/lib/api";
import { useUnits } from "@/lib/queries";
import type { Unit, UnitWithUsage } from "@/lib/types";
import { useT } from "@/lib/i18n";

/** Manage the units of measure. Units in use can be renamed but not deleted. */
export function UnitsDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  return (
    <Modal open onClose={onClose} title={t("Units")}>
      <UnitsPanel />
    </Modal>
  );
}

export function UnitsPanel() {
  const t = useT();
  const queryClient = useQueryClient();
  const units = useUnits();
  const [symbol, setSymbol] = useState("");
  const [name, setName] = useState("");
  const [editing, setEditing] = useState<{ id: number; symbol: string; name: string } | null>(null);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["units"] });
    queryClient.invalidateQueries({ queryKey: ["offerings"] });
  };
  const create = useMutation({
    mutationFn: () => api<Unit>("/units", { method: "POST", json: { symbol, name } }),
    onSuccess: () => {
      setSymbol("");
      setName("");
      refresh();
    },
  });
  const update = useMutation({
    mutationFn: (u: { id: number; symbol: string; name: string }) =>
      api<Unit>(`/units/${u.id}`, { method: "PUT", json: { symbol: u.symbol, name: u.name } }),
    onSuccess: () => {
      setEditing(null);
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api<void>(`/units/${id}`, { method: "DELETE" }),
    onSuccess: refresh,
  });

  return (
    <div>
      <p className="mb-3 text-sm text-muted">
        {t("Products and items keep stock in a base unit, and can have packs such as 1 box = 20 pcs.")}
      </p>
      <form
        className="mb-4 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (symbol.trim() && name.trim()) create.mutate();
        }}
      >
        <Input placeholder={t("Symbol, e.g. box")} aria-label={t("New unit symbol")} value={symbol} onChange={(e) => setSymbol(e.target.value)} className="w-32" />
        <Input placeholder={t("Name, e.g. Box")} aria-label={t("New unit name")} value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={create.isPending}>
          {t("Add")}
        </Button>
      </form>
      <ErrorNote error={create.error ?? update.error ?? remove.error} />
      {units.isPending ? (
        <Spinner />
      ) : (
        <ul className="divide-y divide-border">
          {units.data?.map((u: UnitWithUsage) =>
            editing?.id === u.id ? (
              <li key={u.id} className="flex items-center gap-2 py-2">
                <Input aria-label={t("Symbol")} value={editing.symbol} onChange={(e) => setEditing({ ...editing, symbol: e.target.value })} className="h-9 w-28" />
                <Input aria-label={t("Name")} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} className="h-9" />
                <button
                  onClick={() => update.mutate(editing)}
                  className="rounded-md p-1.5 text-primary hover:bg-primary-soft"
                  aria-label={t("Save unit")}
                >
                  <Check className="size-4" />
                </button>
                <button onClick={() => setEditing(null)} className="rounded-md p-1.5 text-muted hover:bg-surface-2" aria-label={t("Cancel editing")}>
                  <X className="size-4" />
                </button>
              </li>
            ) : (
              <li key={u.id} className="flex items-center gap-3 py-2 text-sm">
                <span className="w-16 font-mono font-medium">{u.symbol}</span>
                <span className="flex-1">{u.name}</span>
                <span className="text-xs text-muted">{u.usage ? t("{usage} in use", { usage: u.usage }) : t("unused")}</span>
                <button
                  onClick={() => setEditing({ id: u.id, symbol: u.symbol, name: u.name })}
                  className="rounded-md p-1.5 text-muted hover:bg-surface-2 hover:text-text"
                  aria-label={t("Edit {symbol}", { symbol: u.symbol })}
                >
                  <Pencil className="size-4" />
                </button>
                <button
                  onClick={() => remove.mutate(u.id)}
                  disabled={u.usage > 0}
                  title={u.usage > 0 ? t("In use, so it can't be deleted") : undefined}
                  className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger disabled:opacity-30 disabled:hover:bg-transparent disabled:hover:text-muted"
                  aria-label={t("Delete {symbol}", { symbol: u.symbol })}
                >
                  <Trash2 className="size-4" />
                </button>
              </li>
            ),
          )}
        </ul>
      )}
    </div>
  );
}
