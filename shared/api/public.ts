"use client";

import { useQuery } from "@tanstack/react-query";

import { ApiError, toError } from "./client";
import { msg } from "../i18n";

/**
 * What the public pages (portal, mobile app page) read: open endpoints only, no sign-in. Mirrors
 * ShopService.StoreDto (the parts used) and MobileAppService.PublicDto.
 */
export interface PublicStore {
  name: string;
  hasLogo: boolean;
  open: boolean;
  pickupEnabled: boolean;
  pickupBranches: { id: number; name: string; address: string | null }[];
  delivery: { enabled: boolean };
}

export interface MobileAppInfo {
  versionName: string | null;
  releaseNotes: string | null;
  updatedAt: string;
  apk: { fileName: string; sizeBytes: number; sha256: string; uploadedAt: string } | null;
  playStoreUrl: string | null;
  appStoreUrl: string | null;
  testflightUrl: string | null;
  androidMinimum: string;
  iosMinimum: string;
}

export async function publicApi<T>(path: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, { credentials: "omit" });
  } catch {
    throw new ApiError(503, msg("Can't reach the server right now. Check your connection and try again."));
  }
  if (!res.ok) throw await toError(res);
  return (await res.json()) as T;
}

export function usePublicStore() {
  return useQuery({ queryKey: ["public", "store"], queryFn: () => publicApi<PublicStore>("/shop/store"), staleTime: 60_000 });
}

export function useMobileApp() {
  return useQuery({ queryKey: ["public", "mobile-app"], queryFn: () => publicApi<MobileAppInfo>("/mobile-app"), staleTime: 60_000 });
}
