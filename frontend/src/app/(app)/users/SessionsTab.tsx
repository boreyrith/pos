"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useState } from "react";

import { SessionList } from "@/components/account/SessionList";
import { Card, ErrorNote, Input, Spinner } from "@/components/ui";
import { api } from "@/lib/api";
import { translate, useT } from "@/lib/i18n";
import type { UserSession } from "@/lib/types";

const key = ["sessions"] as const;

/** Everyone signed in now (super admin): who, on what, from where, and signing any of them out. */
export function SessionsTab() {
  const t = useT();
  const queryClient = useQueryClient();
  const [q, setQ] = useState("");
  const list = useQuery({ queryKey: key, queryFn: () => api<UserSession[]>("/sessions"), refetchInterval: 30_000 });
  const end = useMutation({
    mutationFn: (s: UserSession) => api<void>(`/sessions/${s.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }),
  });
  if (list.isPending) return <Spinner />;
  if (list.error) return <ErrorNote error={list.error} />;
  const term = q.trim().toLowerCase();
  const shown = term
    ? list.data.filter((s) => [s.fullName, s.username, s.roleName, s.device, s.ipAddress ?? ""].some((x) => x.toLowerCase().includes(term)))
    : list.data;
  const people = new Set(list.data.map((s) => s.userId)).size;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm text-muted">
          {t("{sessions} sessions open by {people} people. Signing one out ends it straight away.", { sessions: list.data.length, people })}
        </p>
        <div className="relative ml-auto w-full sm:w-72">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("Name, device or IP")} className="pl-9" aria-label={t("Search sessions")} />
        </div>
      </div>
      <ErrorNote error={end.error} />
      <Card className="px-4">
        {shown.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted">{t("No sessions match.")}</p>
        ) : (
          <SessionList
            sessions={shown}
            showUser
            ending={end.isPending ? (end.variables?.id ?? null) : null}
            onEnd={(s) =>
              window.confirm(translate("Sign {name} out on {device}? They are signed out straight away.", { name: s.fullName, device: translate(s.device) })) &&
              end.mutate(s)
            }
          />
        )}
      </Card>
    </div>
  );
}
