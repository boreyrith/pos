"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { useAuth } from "../auth/auth";
import { useT } from "../i18n";
import { goTo } from "../zones";
import { homePath } from "./AppShell";
import { Spinner } from "./ui";

/** Sends the visitor to their first page (in either app), or to sign in. */
export function HomeRedirect() {
  const { state } = useAuth();
  const router = useRouter();
  const t = useT();
  useEffect(() => {
    if (state.status === "signed-out") router.replace("/login");
    if (state.status === "signed-in") goTo(homePath(state.user), router);
  }, [state, router]);
  return (
    <div className="grid min-h-screen place-items-center">
      <Spinner label={t("Loading")} />
    </div>
  );
}
