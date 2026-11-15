import type { Metadata } from "next";
import { Suspense } from "react";

import { UsersView } from "@/app/(app)/users/UsersView";

export const metadata: Metadata = { title: "Users" };

export default function UsersPage() {
  return (
    <Suspense>
      <UsersView />
    </Suspense>
  );
}
