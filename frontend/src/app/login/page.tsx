import "./login.css";

import type { Metadata } from "next";
import { Suspense } from "react";

import { BrandPanel } from "./BrandPanel";
import { LoginForm } from "./LoginForm";

export const metadata: Metadata = { title: "Sign in" };

/** Split screen on wide screens: the brand panel on the left, the form on the right. Phones get the form alone. */
export default function LoginPage() {
  return (
    <main className="grid min-h-dvh bg-surface lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <BrandPanel />
      <Suspense>
        <LoginForm />
      </Suspense>
    </main>
  );
}
