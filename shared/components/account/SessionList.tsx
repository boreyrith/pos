"use client";

import { LayoutDashboard, Monitor, ShoppingCart, Smartphone, Tablet } from "lucide-react";

import { Badge, Button } from "../ui";
import { dateTime } from "../../utils/format";
import { msg, useT } from "../../i18n";
import type { SessionPlatform, UserSession } from "../../types";

const KIND_ICON = { DESKTOP: Monitor, PHONE: Smartphone, TABLET: Tablet };

/** The platforms, in the order the list shows them: each has its own sessions. */
const PLATFORMS: { key: SessionPlatform; label: string; icon: typeof Monitor }[] = [
  { key: "CTL", label: msg("Administration"), icon: LayoutDashboard },
  { key: "CAS", label: msg("Till"), icon: ShoppingCart },
  { key: "APP", label: msg("Mobile app"), icon: Smartphone },
];

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
 * Signed-in devices, grouped by platform (administration, the till, the mobile app): what each is,
 * where and when it was last used, and a sign-out button. {@code showUser} adds who it belongs to
 * (the super admin's list of everyone).
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
  const place = (ip: string | null) => (ip && /^(::1|0:0:0:0:0:0:0:1|127\.|::ffff:127\.)/.test(ip) ? t("this computer") : ip);
  return (
    <div className="flex flex-col gap-4">
      {PLATFORMS.map((p) => {
        const group = sessions.filter((s) => s.platform === p.key);
        if (group.length === 0) return null;
        return (
          <section key={p.key} aria-label={t(p.label)}>
            <h3 className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted uppercase">
              <p.icon className="size-3.5" aria-hidden />
              {t(p.label)}
              <span className="font-normal normal-case">· {group.length}</span>
            </h3>
            <SessionRows sessions={group} onEnd={onEnd} ending={ending} showUser={showUser} place={place} lastActive={lastActive} />
          </section>
        );
      })}
    </div>
  );
}

function SessionRows({
  sessions,
  onEnd,
  ending,
  showUser,
  place,
  lastActive,
}: {
  sessions: UserSession[];
  onEnd: (s: UserSession) => void;
  ending?: string | null;
  showUser?: boolean;
  place: (ip: string | null) => string | null;
  lastActive: (iso: string) => string;
}) {
  const t = useT();
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
