"use client";

import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";

/** Active storages this person may change stock in: own branch for managers, any for admins. */
export function useEditableStorages() {
  const user = useUser();
  const { branches, current } = useBranch();
  const list = branches
    .filter((b) => b.active && (hasPermission(user, "ALL_BRANCHES") || b.id === user.branchId))
    .flatMap((b) => b.storages.filter((s) => s.active).map((s) => ({ ...s, branchName: b.name })));
  const preferred =
    list.find((s) => s.branchId === current?.id && s.selling) ?? list.find((s) => s.branchId === current?.id) ?? list[0];
  return { storages: list, defaultId: preferred?.id ?? null };
}
