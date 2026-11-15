"use client";

import { useState } from "react";

import { cx } from "./ui";

/** The company logo (public, set in Settings), or a "POS" badge when there is none. */
export function StoreLogo({ className }: { className?: string }) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <div
        className={cx(
          "grid place-items-center bg-linear-to-b from-primary to-primary-hover text-sm font-semibold tracking-wide text-on-primary",
          className,
        )}
      >
        POS
      </div>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element -- the company logo, public; falls back to the badge
    <img src="/api/settings/company/logo" alt="" onError={() => setFailed(true)} className={cx("bg-white object-contain", className)} />
  );
}
