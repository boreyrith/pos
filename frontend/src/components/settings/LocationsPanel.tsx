"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal } from "@/components/ui";
import { api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import type { Branch, Storage } from "@/lib/types";
import { useT } from "@/lib/i18n";

/** Admins manage branches; managers can add and edit storages in their own branch. */
export function LocationsPanel() {
  const t = useT();
  const user = useUser();
  const admin = hasPermission(user, "BRANCHES_MANAGE");
  const storages = hasPermission(user, "STORAGES_MANAGE");
  const everywhere = hasPermission(user, "ALL_BRANCHES");
  const { branches } = useBranch();
  const queryClient = useQueryClient();
  const [branchDialog, setBranchDialog] = useState<Branch | "new" | null>(null);
  const [storageDialog, setStorageDialog] = useState<{ branch: Branch; storage: Storage | null } | null>(null);

  const update = useMutation({
    mutationFn: (s: Storage & { makeSelling?: boolean }) =>
      api<Storage>(`/storages/${s.id}`, {
        method: "PUT",
        json: { name: s.name, selling: s.makeSelling ? true : undefined, active: s.active },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["branches"] }),
  });

  return (
    <div className="flex flex-col gap-4">
      {admin && (
        <div className="flex justify-end">
          <Button onClick={() => setBranchDialog("new")}>
            <Plus className="size-4" aria-hidden />{" "}{t("New branch")}
          </Button>
        </div>
      )}
      <ErrorNote error={update.error} />
      {branches.map((b) => {
        const editable = storages && (everywhere || b.id === user.branchId);
        return (
          <Card key={b.id} className="p-4">
            <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="font-semibold">{b.name}</h2>
                  <span className="font-mono text-xs text-muted">{b.code}</span>
                  {!b.active && <Badge>{t("Inactive")}</Badge>}
                  {b.id === user.branchId && <Badge tone="primary">{t("Your branch")}</Badge>}
                </div>
                {b.address && <p className="text-sm text-muted">{b.address}</p>}
              </div>
              <div className="flex gap-2">
                {admin && (
                  <Button size="sm" variant="ghost" onClick={() => setBranchDialog(b)}>
                    {t("Edit branch")}
                  </Button>
                )}
                {editable && (
                  <Button size="sm" variant="secondary" onClick={() => setStorageDialog({ branch: b, storage: null })}>
                    <Plus className="size-4" aria-hidden />{" "}{t("Storage")}
                  </Button>
                )}
              </div>
            </div>
            <ul className="divide-y divide-border">
              {b.storages.map((s) => (
                <li key={s.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                  <span className={s.active ? "font-medium" : "text-muted"}>{s.name}</span>
                  {s.selling && <Badge tone="success">{t("Selling")}</Badge>}
                  {!s.active && <Badge>{t("Inactive")}</Badge>}
                  {editable && (
                    <span className="ml-auto flex gap-1">
                      {!s.selling && s.active && (
                        <Button size="sm" variant="ghost" onClick={() => update.mutate({ ...s, makeSelling: true })}>
                          {t("Make selling")}
                        </Button>
                      )}
                      <Button size="sm" variant="ghost" onClick={() => setStorageDialog({ branch: b, storage: s })}>
                        {t("Edit")}
                      </Button>
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </Card>
        );
      })}
      {branchDialog && <BranchDialog branch={branchDialog === "new" ? null : branchDialog} onClose={() => setBranchDialog(null)} />}
      {storageDialog && <StorageDialog {...storageDialog} onClose={() => setStorageDialog(null)} />}
    </div>
  );
}

function BranchDialog({ branch, onClose }: { branch: Branch | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    code: branch?.code ?? "",
    name: branch?.name ?? "",
    address: branch?.address ?? "",
    active: branch?.active ?? true,
  });
  const save = useMutation({
    mutationFn: () =>
      branch
        ? api<Branch>(`/branches/${branch.id}`, { method: "PUT", json: form })
        : api<Branch>("/branches", { method: "POST", json: form }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["branches"] });
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={branch ? t("Edit {name}", { name: branch.name }) : t("New branch")}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid grid-cols-[120px_1fr] gap-3">
          <Field label={t("Code")} hint="e.g. SRP">
            <Input required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} maxLength={20} />
          </Field>
          <Field label={t("Name")}>
            <Input autoFocus required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
        </div>
        <Field label={t("Address")}>
          <Input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
        </Field>
        {branch ? (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
            {t("Active")}
          </label>
        ) : (
          <p className="text-xs text-muted">{t("A “Shop floor” selling storage is created with the branch. Add more storages after.")}</p>
        )}
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {branch ? t("Save") : t("Create branch")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function StorageDialog({ branch, storage, onClose }: { branch: Branch; storage: Storage | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [name, setName] = useState(storage?.name ?? "");
  const [active, setActive] = useState(storage?.active ?? true);
  const save = useMutation({
    mutationFn: () =>
      storage
        ? api<Storage>(`/storages/${storage.id}`, { method: "PUT", json: { name, active } })
        : api<Storage>(`/branches/${branch.id}/storages`, { method: "POST", json: { name } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["branches"] });
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={storage ? t("Edit {name}", { name: storage.name }) : t("New storage at {name}", { name: branch.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label={t("Name")} hint={t("e.g. Back store, Warehouse, Cold room")}>
          <Input autoFocus required value={name} onChange={(e) => setName(e.target.value)} maxLength={80} />
        </Field>
        {storage && !storage.selling && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            {t("Active (a storage must be empty before it can be deactivated)")}
          </label>
        )}
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {storage ? t("Save") : t("Add storage")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
