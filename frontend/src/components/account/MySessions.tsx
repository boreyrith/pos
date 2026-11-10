"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { Button, ErrorNote, Spinner } from "@/components/ui";
import { api } from "@/lib/api";
import { translate, useT } from "@/lib/i18n";
import type { UserSession } from "@/lib/types";

import { SessionList } from "./SessionList";

const key = ["auth", "sessions"] as const;

/** Your signed-in devices; sign any other one out, or all of them. */
export function MySessions() {
  const t = useT();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: key, queryFn: () => api<UserSession[]>("/auth/sessions"), refetchInterval: 30_000 });
  const end = useMutation({
    mutationFn: (s: UserSession) => api<void>(`/auth/sessions/${s.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }),
  });
  const endOthers = useMutation({
    mutationFn: () => api<{ ended: number }>("/auth/sessions/end-others", { method: "POST" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }),
  });
  if (list.isPending) return <Spinner />;
  if (list.error) return <ErrorNote error={list.error} />;
  const others = list.data.filter((s) => !s.current).length;
  return (
    <div>
      <SessionList
        sessions={list.data}
        ending={end.isPending ? (end.variables?.id ?? null) : null}
        onEnd={(s) => window.confirm(translate("Sign out {device}? It is signed out straight away.", { device: translate(s.device) })) && end.mutate(s)}
      />
      <ErrorNote error={end.error ?? endOthers.error} />
      {others > 0 && (
        <Button
          variant="secondary"
          className="mt-2 w-full"
          loading={endOthers.isPending}
          onClick={() => window.confirm(translate("Sign out your other {n} devices?", { n: others })) && endOthers.mutate()}
        >
          {t("Sign out all other devices")}
        </Button>
      )}
    </div>
  );
}
