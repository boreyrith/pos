"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useMemo, useState } from "react";

import { api, setBranchOverride } from "./api";
import { useUser } from "./auth";
import type { Branch, Storage } from "./types";

const STORAGE_KEY = "pos.branch";

interface BranchContextValue {
  branches: Branch[];
  /** The branch the app acts for: the person's own, or the admin's pick. */
  current: Branch | null;
  sellingStorage: Storage | null;
  /** Admins only. */
  switchBranch: (branchId: number) => void;
  canSwitch: boolean;
}

const Ctx = createContext<BranchContextValue | null>(null);

function readSaved(): number | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? Number(raw) : null;
  } catch {
    return null;
  }
}

/** Must sit below the signed-in check: it reads the current user. */
export function BranchProvider({ children }: { children: React.ReactNode }) {
  const user = useUser();
  const queryClient = useQueryClient();
  const canSwitch = user.permissions.includes("ALL_BRANCHES");
  const [picked, setPicked] = useState<number | null>(() => {
    const saved = canSwitch ? readSaved() : null;
    setBranchOverride(saved);
    return saved;
  });

  const branchesQuery = useQuery({ queryKey: ["branches"], queryFn: () => api<Branch[]>("/branches"), staleTime: 60_000 });
  const branches = useMemo(() => branchesQuery.data ?? [], [branchesQuery.data]);

  const currentId = canSwitch && picked && branches.some((b) => b.id === picked && b.active) ? picked : user.branchId;
  const current = branches.find((b) => b.id === currentId) ?? null;
  const sellingStorage = current?.storages.find((s) => s.selling) ?? null;

  const switchBranch = useCallback(
    (branchId: number) => {
      if (!canSwitch) return;
      try {
        localStorage.setItem(STORAGE_KEY, String(branchId));
      } catch {
        // not remembered, still switched
      }
      setBranchOverride(branchId);
      setPicked(branchId);
      // Everything the server scopes by branch (sell screen stock, catalog) must reload.
      queryClient.invalidateQueries({ predicate: (q) => q.queryKey[0] !== "branches" && q.queryKey[0] !== "store" });
    },
    [canSwitch, queryClient],
  );

  const value = useMemo(
    () => ({ branches, current, sellingStorage, switchBranch, canSwitch }),
    [branches, current, sellingStorage, switchBranch, canSwitch],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useBranch() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useBranch must be used inside BranchProvider");
  return ctx;
}

/** "Main branch / Shop floor" */
export function placeLabel(branches: Branch[], storageId: number) {
  for (const b of branches) {
    const s = b.storages.find((x) => x.id === storageId);
    if (s) return `${b.name} / ${s.name}`;
  }
  return `Storage #${storageId}`;
}
