"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { PageHeader, cx } from "@pos/shared/components/ui";
import { hasPermission, useUser } from "@pos/shared/auth/auth";
import type { Permission } from "@pos/shared/types";
import { msg, useT } from "@pos/shared/i18n";

import { AuditLogTab } from "@/app/(app)/users/AuditLogTab";
import { RolesTab } from "@/app/(app)/users/RolesTab";
import { SessionsTab } from "@/app/(app)/users/SessionsTab";
import { UsersTab } from "@/app/(app)/users/UsersTab";

/** {@code superAdmin}: only the super admin sees the tab. */
const TABS: { key: "users" | "roles" | "sessions" | "audit"; label: string; any: Permission[]; superAdmin?: boolean }[] = [
  { key: "users", label: msg("Users"), any: ["USERS_VIEW"] },
  { key: "roles", label: msg("Roles & permissions"), any: ["USERS_VIEW", "ROLES_MANAGE"] },
  { key: "sessions", label: msg("Sessions"), any: [], superAdmin: true },
  { key: "audit", label: msg("Audit log"), any: ["AUDIT_VIEW"] },
];

export function UsersView() {
  const t = useT();
  const me = useUser();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tabs = TABS.filter((x) => (x.superAdmin ? me.role.superAdmin : hasPermission(me, ...x.any)));
  const tab = tabs.find((x) => x.key === params.get("tab")) ?? tabs[0];
  if (!tab) return null;

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader title={t("Users & access")} />
      <div className="mb-4 flex gap-1 border-b border-border" role="tablist" aria-label={t("Users")}>
        {tabs.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={tab.key === x.key}
            onClick={() => router.replace(`${pathname}?tab=${x.key}`)}
            className={cx(
              "-mb-px border-b-2 px-4 py-2 text-sm font-medium",
              tab.key === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            {t(x.label)}
          </button>
        ))}
      </div>
      {tab.key === "users" && <UsersTab />}
      {tab.key === "roles" && <RolesTab />}
      {tab.key === "sessions" && <SessionsTab />}
      {tab.key === "audit" && <AuditLogTab />}
    </div>
  );
}
