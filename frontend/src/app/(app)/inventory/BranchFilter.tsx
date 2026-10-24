"use client";

import { Select } from "@/components/ui";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { useT } from "@/lib/i18n";

/** People who work across branches choose one or all; everyone else sees their own branch's name. */
export function BranchFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const t = useT();
  const user = useUser();
  const { branches } = useBranch();
  if (!hasPermission(user, "ALL_BRANCHES")) {
    return <span className="self-center text-sm text-muted">{user.branchName}</span>;
  }
  return (
    <Select aria-label={t("Branch")} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t("All branches")}</option>
      {branches.map((b) => (
        <option key={b.id} value={b.id}>
          {b.name}
        </option>
      ))}
    </Select>
  );
}
