"use client";

import { useQuery } from "@tanstack/react-query";
import { TriangleAlert } from "lucide-react";

import { Card, ErrorNote, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { translateServer, useT } from "@/lib/i18n";

interface SystemItem {
  label: string;
  value: string;
  /** The environment variable that changes it. */
  env: string | null;
  note: string | null;
  warning: boolean;
}

interface SystemGroup {
  title: string;
  items: SystemItem[];
}

/**
 * How this installation is set up. Read-only: these come from the server's environment variables
 * (see .env.example), so each row names the variable to change and restart.
 */
export function SystemPanel() {
  const t = useT();
  const info = useQuery({
    queryKey: ["system", "info"],
    queryFn: () => api<SystemGroup[]>("/system/info"),
  });
  if (info.isPending) return <Spinner />;
  if (info.error) return <ErrorNote error={info.error} />;
  const warnings = info.data
    .flatMap((g) => g.items)
    .filter((i) => i.warning).length;

  return (
    <div className="flex flex-col gap-4">
      <p className="rounded-lg bg-surface-2 px-4 py-3 text-sm text-muted">
        {t("These are set when the server is installed, with environment variables (see {file}), and take effect after a restart. They can't be changed here.", { file: ".env.example" })}
        {warnings > 0 && (
          <span className="mt-1 flex items-center gap-1.5 text-warning">
            <TriangleAlert className="size-4 shrink-0" aria-hidden />
            {warnings === 1 ? t("1 item needs attention before going live.") : t("{n} items need attention before going live.", { n: warnings })}
          </span>
        )}
      </p>
      {info.data.map((g) => (
        <Card key={g.title} className="overflow-hidden">
          <h3 className="border-b border-border px-4 py-2.5 text-sm font-semibold">
            {t(g.title)}
          </h3>
          <dl className="divide-y divide-border">
            {g.items.map((i) => (
              <div
                key={i.label}
                className="grid gap-1 px-4 py-2.5 text-sm sm:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] sm:gap-4"
              >
                <dt className="text-muted">{t(i.label)}</dt>
                <dd className="min-w-0">
                  <span
                    className={cx("font-medium", i.warning && "text-warning")}
                  >
                    {i.warning && (
                      <TriangleAlert
                        className="mr-1 inline size-3.5 -translate-y-px"
                        aria-label={t("Needs attention")}
                      />
                    )}
                    {translateServer(i.value)}
                  </span>
                  {(i.note || i.env) && (
                    <span className="block text-xs text-muted">
                      {translateServer(i.note)}
                      {i.note && i.env ? " · " : ""}
                      {i.env && (
                        <code className="rounded bg-surface-2 px-1 py-0.5 text-[11px]">
                          {i.env}
                        </code>
                      )}
                    </span>
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </Card>
      ))}
    </div>
  );
}
