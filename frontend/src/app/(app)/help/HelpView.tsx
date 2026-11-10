"use client";

import { PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** Help for everyone signed in: the user manual (its own page, public/manual, built from docs/user-manual.html). */
export function HelpView() {
  const t = useT();
  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader
        title={t("User manual")}
        actions={
          <a href="/manual/index.html" target="_blank" rel="noopener" className="text-sm text-primary hover:underline">
            {t("Open in a new tab")}
          </a>
        }
      />
      <iframe
        src="/manual/index.html"
        title={t("User manual")}
        className="h-[calc(100vh-14rem)] min-h-[32rem] w-full rounded-xl border border-border bg-surface"
      />
    </div>
  );
}
