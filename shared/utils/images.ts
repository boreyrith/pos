/**
 * Shrinks a photo in the browser before upload: at most `maxSide` pixels on the long side,
 * re-encoded as WebP (or JPEG where WebP isn't supported). Small images are sent as they are.
 * Keeps uploads well under the server's 2 MB limit and catalog pages fast.
 */
export async function prepareImage(file: File, maxSide = 1024): Promise<Blob> {
  if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
    throw new Error("Choose a PNG, JPEG or WebP image");
  }
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  if (scale === 1 && file.size <= 300 * 1024) {
    bitmap.close();
    return file;
  }
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const encode = (type: string) => new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, 0.85));
  const webp = await encode("image/webp");
  const blob = webp && webp.type === "image/webp" ? webp : await encode("image/jpeg");
  if (!blob) throw new Error("The image could not be processed");
  return blob;
}
