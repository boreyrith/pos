"use client";

import { Monitor, Smartphone, Tablet } from "lucide-react";

import { Badge, Button } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { useT } from "@/lib/i18n";
import type { UserSession } from "@/lib/types";

const KIND_ICON = { DESKTOP: Monitor, PHONE: Smartphone, TABLET: Tablet };

/** "Active now", "5 min ago", "3 h ago", else the date. */
function useLastActive() {
  const t = useT();
  return (iso: string) => {
    const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
    if (minutes < 2) return t("Active now");
    if (minutes < 60) return t("{n} min ago", { n: minutes });
    if (minutes < 24 * 60) return t("{n} h ago", { n: Math.floor(minutes / 60) });
    return dateTime(iso);
  };
}

/**
 * Signed-in devices: what each is, where and when it was last used, and a sign-out button.
 * {@code showUser} adds who it belongs to (the super admin's list of everyone).
 */
export function SessionList({
  sessions,
  onEnd,
  ending,
  showUser,
}: {
  sessions: UserSession[];
  onEnd: (s: UserSession) => void;
  ending?: string | null;
  showUser?: boolean;
}) {
  const t = useT();
  const lastActive = useLastActive();
  // Without a proxy in front (development), requests come from this machine itself.
  const place = (ip: string | null) => (ip && /^(::1|127\.|::ffff:127\.)/.test(ip) ? t("this computer") : ip);
  return (
    <ul className="divide-y divide-border">
      {sessions.map((s) => {
        const Icon = KIND_ICON[s.kind];
        return (
          <li key={s.id} className="flex items-center gap-3 py-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-surface-2 text-muted">
              <Icon className="size-5" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                {showUser && <span className="font-medium">{s.fullName}</span>}
                <span className={showUser ? "text-sm text-muted" : "font-medium"}>{t(s.device)}</span>
                {s.current && <Badge tone="success">{t("This device")}</Badge>}
              </div>
              <div className="mt-0.5 text-xs text-muted">
                {[showUser ? `${s.username} · ${s.roleName}` : null, place(s.ipAddress), lastActive(s.lastUsedAt), t("signed in {when}", { when: dateTime(s.createdAt) })]
                  .filter(Boolean)
                  .join(" · ")}
              </div>
            </div>
            {!s.current && (
              <Button size="sm" variant="secondary" loading={ending === s.id} onClick={() => onEnd(s)}>
                {t("Sign out")}
              </Button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
