"use client";

import { ImagePlus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { Button } from "@/components/ui";
import { prepareImage } from "@/lib/images";
import { translate, useT } from "@/lib/i18n";

/** What should happen to the image when the form is saved. */
export type ImageChange = { kind: "keep" } | { kind: "upload"; blob: Blob } | { kind: "remove" };

/**
 * Pick, preview, replace or remove an entry's image. Nothing is sent until the form is saved; the
 * parent gets an {@link ImageChange}. Photos are shrunk in the browser first.
 */
export function ImageField({
  currentUrl,
  change,
  onChange,
}: {
  /** The saved image, if any. */
  currentUrl: string | null;
  change: ImageChange;
  onChange: (change: ImageChange) => void;
}) {
  const t = useT();
  const fileRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Preview a picked file without uploading it; free the preview when it changes.
  const pendingUrl = useMemo(() => (change.kind === "upload" ? URL.createObjectURL(change.blob) : null), [change]);
  useEffect(
    () => () => {
      if (pendingUrl) URL.revokeObjectURL(pendingUrl);
    },
    [pendingUrl],
  );

  const shown = change.kind === "upload" ? pendingUrl : change.kind === "remove" ? null : currentUrl;

  async function pick(file: File | undefined) {
    if (!file) return;
    setError(null);
    setBusy(true);
    try {
      const blob = await prepareImage(file);
      if (blob.size > 2 * 1024 * 1024) throw new Error(translate("The image is still over 2 MB after shrinking; try another one"));
      onChange({ kind: "upload", blob });
    } catch (e) {
      setError(e instanceof Error ? e.message : translate("The image could not be used"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <span className="font-medium">{t("Image")}</span>
      <div className="flex items-center gap-4">
        <div
          className="grid size-24 shrink-0 place-items-center overflow-hidden rounded-xl border border-dashed border-border bg-surface-2"
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            pick(e.dataTransfer.files[0]);
          }}
        >
          {shown ? (
            // eslint-disable-next-line @next/next/no-img-element -- a blob preview or our own API image
            <img src={shown} alt={t("Image preview")} className="size-full object-cover" />
          ) : (
            <span className="px-2 text-center text-xs text-muted">{t("No image")}</span>
          )}
        </div>
        <div className="flex flex-col gap-2">
          <input
            ref={fileRef}
            type="file"
            accept="image/png,image/jpeg,image/webp"
            className="hidden"
            onChange={(e) => {
              pick(e.target.files?.[0]);
              e.target.value = "";
            }}
          />
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => fileRef.current?.click()} loading={busy}>
              <ImagePlus className="size-4" aria-hidden /> {shown ? t("Replace") : t("Upload")}
            </Button>
            {shown && (
              <Button type="button" variant="ghost" size="sm" onClick={() => onChange(currentUrl && change.kind === "upload" ? { kind: "keep" } : { kind: "remove" })}>
                <Trash2 className="size-4" aria-hidden /> {change.kind === "upload" && currentUrl ? t("Undo") : t("Remove")}
              </Button>
            )}
            {change.kind === "remove" && currentUrl && (
              <Button type="button" variant="ghost" size="sm" onClick={() => onChange({ kind: "keep" })}>
                {t("Undo remove")}
              </Button>
            )}
          </div>
          <span className="text-xs text-muted">
            {t("PNG, JPEG or WebP. Photos are shrunk to 1024 px. Drop a file on the box or use Upload.")}
            {change.kind !== "keep" && t(" Saved when you save the form.")}
          </span>
          {error && <span className="text-xs text-danger">{error}</span>}
        </div>
      </div>
    </div>
  );
}
