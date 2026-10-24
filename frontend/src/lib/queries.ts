"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "./api";
import type { Category, Currency, ReasonOption, StaffMember, StoreSettings, TaxRate, UnitWithUsage, RoleDetail } from "./types";

export function useStoreSettings() {
  return useQuery({
    queryKey: ["store"],
    queryFn: () => api<StoreSettings>("/store"),
    staleTime: 5 * 60_000,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: ["categories"],
    queryFn: () => api<Category[]>("/categories"),
    staleTime: 60_000,
  });
}

export function useStaff() {
  return useQuery({
    queryKey: ["staff"],
    queryFn: () => api<StaffMember[]>("/staff"),
    staleTime: 5 * 60_000,
  });
}

export function useUnits() {
  return useQuery({
    queryKey: ["units"],
    queryFn: () => api<UnitWithUsage[]>("/units"),
    staleTime: 60_000,
  });
}

/** All currencies with current rates. Rates change during the day, so this refreshes. */
export function useCurrencies() {
  return useQuery({
    queryKey: ["currencies"],
    queryFn: () => api<Currency[]>("/currencies"),
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  });
}

/** Active currencies with a rate, base first. */
export function useActiveCurrencies() {
  const q = useCurrencies();
  return (q.data ?? []).filter((c) => (c.active || c.base) && c.rate !== null).sort((a, b) => Number(b.base) - Number(a.base) || a.sortOrder - b.sortOrder);
}

export const logoUrl = (version: number) => `/api/settings/company/logo?v=${version}`;

/** Roles, with whether the current user may assign or edit each. */
export function useRoles() {
  return useQuery({ queryKey: ["roles"], queryFn: () => api<RoleDetail[]>("/roles"), staleTime: 30_000 });
}

export function useTaxRates() {
  return useQuery({ queryKey: ["tax-rates"], queryFn: () => api<TaxRate[]>("/tax-rates"), staleTime: 60_000 });
}

/** A catalog entry's image URL, versioned so a new image is fetched straight away. */
export const offeringImageUrl = (o: { id: number; hasImage: boolean; imageVersion: number }) =>
  o.hasImage ? `/api/offerings/${o.id}/image?v=${o.imageVersion}` : null;

export function useAdjustmentReasons() {
  return useQuery({ queryKey: ["adjustment-reasons"], queryFn: () => api<ReasonOption[]>("/stock-adjustments/reasons"), staleTime: Infinity });
}
