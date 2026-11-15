import { ProductView } from "./ProductView";

export default async function ProductPage({ params }: PageProps<"/products/[id]">) {
  const { id } = await params;
  return <ProductView id={Number(id)} />;
}
