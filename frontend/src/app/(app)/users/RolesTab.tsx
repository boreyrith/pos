"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Lock, Plus, ShieldCheck, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Spinner, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { useRoles, useStoreSettings } from "@/lib/queries";
import type { Permission, PermissionInfo, RoleDetail } from "@/lib/types";
import { translate, translateServer, useT } from "@/lib/i18n";

type Draft = {
  id: number | null;
  name: string;
  description: string;
  permissions: Set<Permission>;
  maxItemDiscountPct: number | null;
  from?: string;
};

/** Adds what the ticked permissions need (refunds need seeing orders...), like the server does. */
function withImplied(selected: Set<Permission>, catalog: PermissionInfo[]) {
  const out = new Set(selected);
  let grew = true;
  while (grew) {
    grew = false;
    for (const p of [...out]) {
      for (const need of catalog.find((c) => c.code === p)?.implies ?? []) {
        if (!out.has(need)) {
          out.add(need);
          grew = true;
        }
      }
    }
  }
  return out;
}

/**
 * Users > Roles & permissions: the built-in roles and any custom ones, and what each may do.
 * "Manage roles" can create, edit and delete; others can look. Nobody can grant a permission
 * they don't have or edit their own role (the server enforces both).
 */
export function RolesTab() {
  const t = useT();
  const me = useUser();
  const manages = hasPermission(me, "ROLES_MANAGE");
  const roles = useRoles();
  const catalog = useQuery({ queryKey: ["roles", "permissions"], queryFn: () => api<PermissionInfo[]>("/roles/permissions"), staleTime: Infinity });
  const [selectedId, setSelectedId] = useState<number | "new" | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);

  if (roles.isPending || catalog.isPending) return <Spinner />;
  if (roles.error || catalog.error) return <ErrorNote error={roles.error ?? catalog.error} />;

  const list = roles.data;
  const selected = selectedId === "new" ? null : (list.find((r) => r.id === selectedId) ?? list[0]);
  const open = (r: RoleDetail) => {
    setSelectedId(r.id);
    setDraft(null);
  };
  const startNew = (copyOf?: RoleDetail) => {
    setSelectedId("new");
    setDraft({
      id: null,
      name: copyOf ? `${copyOf.name} (copy)` : "",
      description: copyOf?.description ?? "",
      // Only what I can grant.
      permissions: new Set((copyOf?.effective ?? []).filter((p) => me.role.superAdmin || me.permissions.includes(p))),
      maxItemDiscountPct: copyOf?.maxItemDiscountPct ?? me.role.maxItemDiscountPct,
      from: copyOf?.name,
    });
  };

  return (
    <div className="grid gap-5 lg:grid-cols-[300px_1fr]">
      <div className="flex flex-col gap-3">
        {manages && (
          <Button onClick={() => startNew()}>
            <Plus className="size-4" aria-hidden />{" "}{t("New role")}
          </Button>
        )}
        <Card className="divide-y divide-border">
          {list.map((r) => (
            <button
              key={r.id}
              onClick={() => open(r)}
              className={cx(
                "flex w-full flex-col gap-0.5 px-4 py-3 text-left text-sm hover:bg-surface-2",
                selectedId !== "new" && selected?.id === r.id && "bg-primary-soft",
              )}
            >
              <span className="flex items-center gap-2 font-medium">
                {r.superAdmin && <ShieldCheck className="size-4 text-primary" aria-hidden />}
                {translateServer(r.name)}
                {!r.builtIn && <Badge>{t("Custom")}</Badge>}
              </span>
              <span className="text-xs text-muted">
                {r.superAdmin ? t("Every permission") : r.effective.length === 1 ? t("1 permission") : t("{n} permissions", { n: r.effective.length })} ·{" "}
                {r.users === 1 ? t("1 user") : t("{n} users", { n: r.users })}
              </span>
            </button>
          ))}
        </Card>
        <p className="text-xs text-muted">
          {t("Changes apply to everyone with the role from their next action. Built-in roles can be adjusted but not renamed or deleted; Super Admin always has everything.")}
        </p>
      </div>

      {selectedId === "new" && draft ? (
        <RoleEditor
          key="new"
          initial={draft}
          catalog={catalog.data}
          readOnly={false}
          onSaved={(r) => {
            setSelectedId(r.id);
            setDraft(null);
          }}
          onCancel={() => setSelectedId(null)}
        />
      ) : selected ? (
        <RoleEditor
          key={selected.id + (selected.updatedAt ?? "")}
          role={selected}
          initial={{
            id: selected.id,
            name: selected.name,
            description: selected.description ?? "",
            permissions: new Set(selected.effective),
            maxItemDiscountPct: selected.maxItemDiscountPct,
          }}
          catalog={catalog.data}
          readOnly={!selected.editable}
          onCopy={manages && !selected.superAdmin ? () => startNew(selected) : undefined}
          onSaved={(r) => setSelectedId(r.id)}
          onDeleted={() => setSelectedId(null)}
        />
      ) : null}
    </div>
  );
}

function RoleEditor({
  role,
  initial,
  catalog,
  readOnly,
  onCopy,
  onSaved,
  onDeleted,
  onCancel,
}: {
  role?: RoleDetail;
  initial: Draft;
  catalog: PermissionInfo[];
  readOnly: boolean;
  onCopy?: () => void;
  onSaved: (r: RoleDetail) => void;
  onDeleted?: () => void;
  onCancel?: () => void;
}) {
  const t = useT();
  const me = useUser();
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const [name, setName] = useState(initial.name);
  const [description, setDescription] = useState(initial.description);
  const [maxDiscount, setMaxDiscount] = useState(initial.maxItemDiscountPct == null ? "" : String(initial.maxItemDiscountPct));
  const [ticked, setTicked] = useState<Set<Permission>>(initial.permissions);
  const all = role?.superAdmin;
  const effective = useMemo(() => (all ? new Set(catalog.map((c) => c.code)) : withImplied(ticked, catalog)), [all, ticked, catalog]);
  const modules = useMemo(() => {
    const groups = new Map<string, PermissionInfo[]>();
    for (const p of catalog) groups.set(p.moduleLabel, [...(groups.get(p.moduleLabel) ?? []), p]);
    return [...groups.entries()];
  }, [catalog]);
  const canGrant = (p: Permission) => me.role.superAdmin || me.permissions.includes(p);
  /** Which ticked permissions need p (so it can't be unticked on its own). */
  const neededBy = (p: Permission) =>
    catalog.filter((c) => c.code !== p && effective.has(c.code) && withImplied(new Set([c.code]), catalog).has(p)).map((c) => c.label);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["roles"] });
    queryClient.invalidateQueries({ queryKey: ["users"] });
  };
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        description: description.trim() || null,
        permissions: [...effective],
        maxItemDiscountPct: effective.has("DISCOUNT_ITEMS") && maxDiscount !== "" ? Number(maxDiscount) : null,
      };
      return initial.id
        ? api<RoleDetail>(`/roles/${initial.id}`, { method: "PUT", json: body })
        : api<RoleDetail>("/roles", { method: "POST", json: body });
    },
    onSuccess: (r) => {
      invalidate();
      onSaved(r);
    },
  });
  const remove = useMutation({
    mutationFn: () => api<void>(`/roles/${initial.id}`, { method: "DELETE" }),
    onSuccess: () => {
      invalidate();
      onDeleted?.();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const toggle = (p: Permission, on: boolean) =>
    setTicked((cur) => {
      const next = new Set(withImplied(cur, catalog));
      if (on) next.add(p);
      else next.delete(p);
      return next;
    });

  return (
    <Card className="p-5">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-base font-semibold">
            {initial.id ? (role?.name ?? name) : initial.from ? t("New role from {from}", { from: initial.from }) : t("New role")}
            {role?.builtIn && <Badge tone="primary">{t("Built-in")}</Badge>}
            {readOnly && (
              <Badge>
                <Lock className="mr-1 inline size-3" aria-hidden />
                {t("View only")}
              </Badge>
            )}
          </h2>
          {role && (
            <p className="text-xs text-muted">
              {role.users} {role.users === 1 ? t("user") : t("users")}
              {role.updatedAt &&
                ` · ${
                  role.updatedBy
                    ? t("changed {when} by {name}", { when: dateTime(role.updatedAt, store.data?.timezone), name: role.updatedBy })
                    : t("changed {when}", { when: dateTime(role.updatedAt, store.data?.timezone) })
                }`}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          {onCopy && (
            <Button size="sm" variant="ghost" onClick={onCopy}>
              <Copy className="size-4" aria-hidden />{" "}{t("Copy as new role")}
            </Button>
          )}
          {role && !role.builtIn && role.editable && (
            <Button
              size="sm"
              variant="ghost"
              loading={remove.isPending}
              onClick={() => {
                if (confirm(translate("Delete the role {name}?", { name: role.name }))) remove.mutate();
              }}
            >
              <Trash2 className="size-4" aria-hidden />{" "}{t("Delete")}
            </Button>
          )}
        </div>
      </div>
      {readOnly && role && !role.superAdmin && (
        <p className="mb-4 rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">
          {role.id === me.role.id
            ? t("This is your own role, so you can't change it. Ask a Super Admin.")
            : hasPermission(me, "ROLES_MANAGE")
              ? t("This role has permissions you don't have, so you can't change it.")
              : t("Your role can view roles but not change them.")}
        </p>
      )}
      {all && <p className="mb-4 rounded-lg bg-primary-soft px-3 py-2 text-sm">{t("Super Admin has every permission, including new ones, and can't be changed.")}</p>}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="mb-5 grid gap-4 sm:grid-cols-2">
          <Field label={t("Name")} error={errors.name} hint={role?.builtIn ? t("Built-in roles keep their name") : undefined}>
            <Input value={name} maxLength={60} required disabled={readOnly || role?.builtIn} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label={t("Description")} error={errors.description}>
            <Input value={description} maxLength={255} disabled={readOnly} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </div>

        {!all && effective.has("DISCOUNT_ITEMS") && (
          <div className="mb-5 max-w-xs">
            <Field
              label={t("Maximum item discount (%)")}
              error={errors.maxItemDiscountPct}
              hint={
                me.role.superAdmin || me.role.maxItemDiscountPct == null
                  ? t("Empty = no limit")
                  : t("At most your own limit ({maxItemDiscountPct}%)", { maxItemDiscountPct: me.role.maxItemDiscountPct })
              }
            >
              <Input
                inputMode="decimal"
                value={maxDiscount}
                disabled={readOnly}
                placeholder={t("No limit")}
                onChange={(e) => setMaxDiscount(e.target.value.replace(/[^\d.]/g, ""))}
              />
            </Field>
          </div>
        )}

        <div className="grid gap-4 xl:grid-cols-2">
          {modules.map(([module, perms]) => (
            <fieldset key={module} className="rounded-lg border border-border p-4">
              <legend className="px-1 text-sm font-semibold">{t(module)}</legend>
              <ul className="flex flex-col gap-2">
                {perms.map((p) => {
                  const on = effective.has(p.code);
                  const needers = on && !all ? neededBy(p.code) : [];
                  const locked = readOnly || all || needers.length > 0 || !canGrant(p.code);
                  return (
                    <li key={p.code}>
                      <label className={cx("flex gap-2 text-sm", locked ? "cursor-default" : "cursor-pointer")}>
                        <input type="checkbox" className="mt-0.5" checked={on} disabled={locked} onChange={(e) => toggle(p.code, e.target.checked)} />
                        <span>
                          <span className="font-medium">{t(p.label)}</span>
                          <span className="block text-xs text-muted">
                            {t(p.description)}
                            {needers.length > 0 && t(" · needed by {join}", { join: needers.map((n) => t(n)).join(", ") })}
                            {!readOnly && !all && !canGrant(p.code) && t(" · you don't have this permission")}
                          </span>
                        </span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            </fieldset>
          ))}
        </div>

        {!readOnly && (
          <>
            <div className="mt-4">
              <ErrorNote error={save.error ?? remove.error} />
            </div>
            <div className="mt-4 flex items-center justify-between gap-3">
              <span className="text-xs text-muted">{t("{size} permissions selected", { size: effective.size })}</span>
              <div className="flex gap-2">
                {onCancel && (
                  <Button type="button" variant="secondary" onClick={onCancel}>
                    {t("Cancel")}
                  </Button>
                )}
                <Button type="submit" loading={save.isPending} disabled={!name.trim()}>
                  {initial.id ? t("Save role") : t("Create role")}
                </Button>
              </div>
            </div>
          </>
        )}
        {readOnly && remove.error && <ErrorNote error={remove.error} />}
      </form>
    </Card>
  );
}
