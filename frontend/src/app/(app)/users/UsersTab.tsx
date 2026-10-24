"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { History, KeyRound, LogOut, Plus, Unlock } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Select, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { dateTime } from "@/lib/format";
import { useRoles, useStoreSettings } from "@/lib/queries";
import type { RoleDetail, User } from "@/lib/types";
import { translate, translateServer, useT } from "@/lib/i18n";

type Status = "" | "active" | "disabled" | "locked" | "must-change";

/**
 * Whether I may change this account. Mirrors the server's AccessRules: Super Admins anyone; others
 * only accounts in their branch (unless they work across branches) whose role is within their own permissions.
 */
function canManageUser(me: User, u: User) {
  if (!hasPermission(me, "USERS_MANAGE")) return false;
  if (me.role.superAdmin) return true;
  if (u.role.superAdmin) return false;
  if (!hasPermission(me, "ALL_BRANCHES") && u.branchId !== me.branchId) return false;
  return u.permissions.every((p) => me.permissions.includes(p));
}

export function UsersTab() {
  const t = useT();
  const me = useUser();
  const roles = useRoles();
  const manages = hasPermission(me, "USERS_MANAGE");
  const store = useStoreSettings();
  const { branches } = useBranch();
  const [editing, setEditing] = useState<User | "new" | null>(null);
  const [q, setQ] = useState("");
  const [role, setRole] = useState("");
  const [branchId, setBranchId] = useState("");
  const [status, setStatus] = useState<Status>("");
  const users = useQuery({ queryKey: ["users"], queryFn: () => api<User[]>("/users") });

  const shown = useMemo(() => {
    const term = q.trim().toLowerCase();
    return (users.data ?? []).filter(
      (u) =>
        (!term || [u.fullName, u.username, u.email ?? "", u.phone ?? ""].some((v) => v.toLowerCase().includes(term))) &&
        (!role || String(u.role.id) === role) &&
        (!branchId || String(u.branchId) === branchId) &&
        (!status ||
          (status === "active" && u.active && !u.locked) ||
          (status === "disabled" && !u.active) ||
          (status === "locked" && u.locked) ||
          (status === "must-change" && u.mustChangePassword)),
    );
  }, [users.data, q, role, branchId, status]);

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input placeholder={t("Search name, username, email, phone")} aria-label={t("Search users")} value={q} onChange={(e) => setQ(e.target.value)} className="w-64" />
        <Select aria-label={t("Role")} value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="">{t("All roles")}</option>
          {roles.data?.map((r) => (
            <option key={r.id} value={r.id}>
              {translateServer(r.name)}
            </option>
          ))}
        </Select>
        <Select aria-label={t("Branch")} value={branchId} onChange={(e) => setBranchId(e.target.value)}>
          <option value="">{t("All branches")}</option>
          {branches.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
            </option>
          ))}
        </Select>
        <Select aria-label={t("Status")} value={status} onChange={(e) => setStatus(e.target.value as Status)}>
          <option value="">{t("Any status")}</option>
          <option value="active">{t("Active")}</option>
          <option value="disabled">{t("Disabled")}</option>
          <option value="locked">{t("Locked")}</option>
          <option value="must-change">{t("Must change password")}</option>
        </Select>
        {manages && (
          <Button className="ml-auto" onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden />{" "}{t("Add user")}
          </Button>
        )}
      </div>
      <Card className="overflow-x-auto">
        {users.isPending ? (
          <Spinner />
        ) : users.error ? (
          <div className="p-4">
            <ErrorNote error={users.error} />
          </div>
        ) : shown.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No users match these filters.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Name")}</th>
                <th className="px-4 py-3 font-medium">{t("Role")}</th>
                <th className="px-4 py-3 font-medium">{t("Branch")}</th>
                <th className="px-4 py-3 font-medium">{t("Status")}</th>
                <th className="px-4 py-3 font-medium">{t("Last sign-in")}</th>
                <th className="px-4 py-3" aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {shown.map((u) => (
                <tr key={u.id} className={u.active ? "hover:bg-surface-2" : "text-muted hover:bg-surface-2"}>
                  <td className="px-4 py-3">
                    <div className="font-medium">
                      {u.fullName} {u.id === me.id && <span className="text-muted">{t("(you)")}</span>}
                    </div>
                    <div className="text-xs text-muted">
                      {u.username}
                      {u.email && ` · ${u.email}`}
                      {u.phone && ` · ${u.phone}`}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={u.role.superAdmin ? "primary" : "neutral"}>{translateServer(u.role.name)}</Badge>
                  </td>
                  <td className="px-4 py-3">{u.branchName}</td>
                  <td className="px-4 py-3">
                    <span className="flex flex-wrap gap-1">
                      {!u.active ? <Badge tone="danger">{t("Disabled")}</Badge> : u.locked ? <Badge tone="warning">{t("Locked")}</Badge> : <Badge tone="success">{t("Active")}</Badge>}
                      {u.mustChangePassword && <Badge>{t("New password due")}</Badge>}
                    </span>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted">{u.lastLoginAt ? dateTime(u.lastLoginAt, store.data?.timezone) : t("Never")}</td>
                  <td className="px-4 py-3 text-right">
                    {canManageUser(me, u) && (
                      <Button variant="ghost" size="sm" onClick={() => setEditing(u)}>
                        {t("Manage")}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {editing === "new" && <CreateUserDialog onClose={() => setEditing(null)} />}
      {editing && editing !== "new" && <ManageUserDialog user={editing} self={editing.id === me.id} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CreateUserDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const me = useUser();
  const { branches } = useBranch();
  const [form, setForm] = useState({
    username: "",
    fullName: "",
    email: "",
    phone: "",
    roleId: "",
    branchId: String(me.branchId),
    password: "",
    mustChangePassword: true,
  });
  const set = (k: keyof typeof form) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () =>
      api<User>("/users", {
        method: "POST",
        json: { ...form, roleId: Number(form.roleId), branchId: Number(form.branchId), email: form.email || null, phone: form.phone || null },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  return (
    <Modal open onClose={onClose} title={t("Add user")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="col-span-2">
          <Field label={t("Full name")} error={errors.fullName}>
            <Input autoFocus required value={form.fullName} onChange={(e) => set("fullName")(e.target.value)} />
          </Field>
        </div>
        <Field label={t("Username")} error={errors.username} hint={t("Letters, numbers, dots, dashes")}>
          <Input required autoCapitalize="none" value={form.username} onChange={(e) => set("username")(e.target.value)} />
        </Field>
        <Field label={t("Temporary password")} error={errors.password} hint={t("At least 8 characters")}>
          <Input type="password" autoComplete="new-password" required value={form.password} onChange={(e) => set("password")(e.target.value)} />
        </Field>
        <Field label={t("Email")} error={errors.email}>
          <Input type="email" value={form.email} onChange={(e) => set("email")(e.target.value)} />
        </Field>
        <Field label={t("Phone")} error={errors.phone}>
          <Input value={form.phone} onChange={(e) => set("phone")(e.target.value)} />
        </Field>
        <RoleBranchFields roleId={form.roleId} branchId={form.branchId} branches={branches} onRole={(v) => set("roleId")(v)} onBranch={(v) => set("branchId")(v)} />
        <label className="col-span-2 flex items-center gap-2 text-sm">
          <input type="checkbox" checked={form.mustChangePassword} onChange={(e) => set("mustChangePassword")(e.target.checked)} />
          {t("They choose their own password when they first sign in")}
        </label>
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!form.roleId}>
            {t("Add user")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function ManageUserDialog({ user, self, onClose }: { user: User; self: boolean; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const store = useStoreSettings();
  const { branches } = useBranch();
  const [form, setForm] = useState({
    fullName: user.fullName,
    email: user.email ?? "",
    phone: user.phone ?? "",
    roleId: String(user.role.id),
    branchId: String(user.branchId),
    active: user.active,
  });
  const [current, setCurrent] = useState(user);
  const [resetOpen, setResetOpen] = useState(false);
  const set = (k: keyof typeof form) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const refresh = (u: User) => {
    setCurrent(u);
    queryClient.invalidateQueries({ queryKey: ["users"] });
  };

  const save = useMutation({
    mutationFn: () =>
      api<User>(`/users/${user.id}`, {
        method: "PUT",
        json: { ...form, roleId: Number(form.roleId), branchId: Number(form.branchId), email: form.email || null, phone: form.phone || null },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      onClose();
    },
  });
  const action = useMutation({
    mutationFn: (path: "unlock" | "sign-out") => api<User>(`/users/${user.id}/${path}`, { method: "POST" }),
    onSuccess: refresh,
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const signsOut = form.roleId !== String(user.role.id) || form.active !== user.active || form.branchId !== String(user.branchId);

  return (
    <Modal open onClose={onClose} title={`${user.fullName} (${user.username})`} wide>
      <div className="mb-5 flex flex-wrap items-center gap-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">
        <span className="text-muted">
          {t("Last sign-in")}{" "}{current.lastLoginAt ? dateTime(current.lastLoginAt, store.data?.timezone) : t("never")}
          {current.passwordChangedAt && t(" · password set {passwordChangedAt}", { passwordChangedAt: dateTime(current.passwordChangedAt, store.data?.timezone) })}
        </span>
        {current.locked && <Badge tone="warning">{t("Locked until {current}", { current: current.lockedUntil && dateTime(current.lockedUntil, store.data?.timezone) })}</Badge>}
        {current.mustChangePassword && <Badge>{t("New password due")}</Badge>}
      </div>

      <div className="mb-5 flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => setResetOpen(true)}>
          <KeyRound className="size-4" aria-hidden />{" "}{t("Reset password")}
        </Button>
        {current.locked && (
          <Button variant="secondary" size="sm" onClick={() => action.mutate("unlock")} loading={action.isPending && action.variables === "unlock"}>
            <Unlock className="size-4" aria-hidden />{" "}{t("Unlock")}
          </Button>
        )}
        {!self && (
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              if (confirm(translate("Sign {fullName} out on every device? Their open screen keeps working for up to 15 minutes.", { fullName: user.fullName }))) action.mutate("sign-out");
            }}
            loading={action.isPending && action.variables === "sign-out"}
          >
            <LogOut className="size-4" aria-hidden />{" "}{t("Sign out everywhere")}
          </Button>
        )}
        <Link href={`/users?tab=audit&userId=${user.id}`} onClick={onClose} className="inline-flex h-8 items-center gap-2 rounded-lg px-3 text-sm font-medium text-text hover:bg-surface-2">
          <History className="size-4" aria-hidden />{" "}{t("Activity")}
        </Link>
      </div>
      {action.isSuccess && action.variables === "sign-out" && <p className="mb-4 text-sm text-success">{t("Signed out everywhere.")}</p>}
      <ErrorNote error={action.error} />

      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="col-span-2">
          <Field label={t("Full name")} error={errors.fullName}>
            <Input required value={form.fullName} onChange={(e) => set("fullName")(e.target.value)} />
          </Field>
        </div>
        <Field label={t("Email")} error={errors.email}>
          <Input type="email" value={form.email} onChange={(e) => set("email")(e.target.value)} />
        </Field>
        <Field label={t("Phone")} error={errors.phone}>
          <Input value={form.phone} onChange={(e) => set("phone")(e.target.value)} />
        </Field>
        <RoleBranchFields
          roleId={form.roleId}
          current={user.role.id}
          branchId={form.branchId}
          branches={branches}
          onRole={(v) => set("roleId")(v)}
          onBranch={(v) => set("branchId")(v)}
          roleLocked={self}
        />
        {!self && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.active} onChange={(e) => set("active")(e.target.checked)} />
            {t("Account can sign in")}
          </label>
        )}
        {signsOut && (
          <p className="col-span-2 text-xs text-muted">{t("The change applies from their next action; they are also signed out on their other devices.")}</p>
        )}
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Close")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {t("Save changes")}
          </Button>
        </div>
      </form>
      {resetOpen && <ResetPasswordDialog user={user} onClose={() => setResetOpen(false)} onDone={refresh} />}
    </Modal>
  );
}

function ResetPasswordDialog({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: (u: User) => void }) {
  const t = useT();
  const [password, setPassword] = useState("");
  const [mustChange, setMustChange] = useState(true);
  const reset = useMutation({
    mutationFn: () => api<User>(`/users/${user.id}/reset-password`, { method: "POST", json: { password, mustChangePassword: mustChange } }),
    onSuccess: (u) => {
      onDone(u);
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={t("Reset password for {fullName}", { fullName: user.fullName })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          reset.mutate();
        }}
      >
        <Field label={t("New password")} hint={t("At least 8 characters. Give it to them in person.")}>
          <Input autoFocus type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={mustChange} onChange={(e) => setMustChange(e.target.checked)} />
          {t("They must choose their own password when they next sign in")}
        </label>
        <p className="text-xs text-muted">{t("This also unlocks the account and signs them out everywhere.")}</p>
        <ErrorNote error={reset.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" disabled={password.length < 8} loading={reset.isPending}>
            {t("Reset password")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function RoleBranchFields({
  roleId,
  current,
  branchId,
  branches,
  onRole,
  onBranch,
  roleLocked,
}: {
  roleId: string;
  /** The role they have now, always listed even if I couldn't assign it. */
  current?: number;
  branchId: string;
  branches: { id: number; name: string; active: boolean }[];
  onRole: (v: string) => void;
  onBranch: (v: string) => void;
  roleLocked?: boolean;
}) {
  const t = useT();
  const me = useUser();
  const roles = useRoles();
  const everywhere = hasPermission(me, "ALL_BRANCHES");
  // Only roles I may give (the server enforces the same rule).
  const options = (roles.data ?? []).filter((r: RoleDetail) => r.assignable || r.id === current);
  const chosen = options.find((r) => String(r.id) === roleId);
  return (
    <>
      <Field
        label={t("Role")}
        hint={roleLocked ? t("You can't change your own role") : (chosen?.description ?? t("Only roles within your own permissions are listed"))}
      >
        <Select value={roleId} disabled={roleLocked} required onChange={(e) => onRole(e.target.value)}>
          <option value="" disabled>
            {t("Choose a role")}
          </option>
          {options.map((r) => (
            <option key={r.id} value={r.id} disabled={!r.assignable && r.id !== current}>
              {translateServer(r.name)}
            </option>
          ))}
        </Select>
      </Field>
      <Field label={t("Branch")} hint={everywhere ? t("Where they sell and manage stock") : t("Staff can only be added to your branch")}>
        <Select value={branchId} onChange={(e) => onBranch(e.target.value)}>
          {branches
            .filter((b) => (b.active || String(b.id) === branchId) && (everywhere || b.id === me.branchId || String(b.id) === branchId))
            .map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
        </Select>
      </Field>
    </>
  );
}
