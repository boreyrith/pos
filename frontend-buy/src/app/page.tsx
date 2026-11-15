import { Suspense } from "react";

import { ShopHome } from "./ShopHome";

export default function HomePage() {
  return (
    <Suspense>
      <ShopHome />
    </Suspense>
  );
}
