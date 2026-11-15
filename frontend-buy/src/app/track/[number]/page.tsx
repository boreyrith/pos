import type { Metadata } from "next";
import { Suspense } from "react";

import { TrackView } from "./TrackView";

export const metadata: Metadata = { title: "Your order" };

export default async function TrackPage({ params }: PageProps<"/track/[number]">) {
  const { number } = await params;
  return (
    <Suspense>
      <TrackView number={decodeURIComponent(number)} />
    </Suspense>
  );
}
