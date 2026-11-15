"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { useT } from "../../i18n";

/** A value to copy (a server address, a private link), with a button that copies it. */
export function CopyBox({ value }: { value: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-center gap-2 rounded-xl border border-border bg-surface-2 p-2 pl-3">
      <code className="min-w-0 flex-1 truncate font-mono text-sm">{value}</code>
      <button
        type="button"
        onClick={() => navigator.clipboard?.writeText(value).then(() => setCopied(true), () => {})}
        className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-lg px-3 text-sm font-medium text-primary hover:bg-surface"
      >
        {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
        {copied ? t("Copied") : t("Copy")}
      </button>
    </div>
  );
}
