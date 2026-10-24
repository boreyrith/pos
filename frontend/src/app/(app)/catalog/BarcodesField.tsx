"use client";

import { Plus, ScanLine, Star, Trash2, Wand2 } from "lucide-react";
import { useState } from "react";

import { CameraScanner } from "@/components/CameraScanner";
import { Badge, Button, ErrorNote, Input, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { translateServer, useT } from "@/lib/i18n";

/**
 * An entry's barcodes: the maker's code, in-store codes, a pack's code… The first is the main one,
 * printed on labels. Each must be unique across the catalog (the server checks); here duplicates in
 * the list are flagged straight away. New codes can be typed, scanned, or generated (in-store EAN-13).
 */
export function BarcodesField({
  value,
  onChange,
  error,
}: {
  value: string[];
  onChange: (codes: string[]) => void;
  error?: string;
}) {
  const t = useT();
  const [scanning, setScanning] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);

  const trimmed = value.map((c) => c.trim());
  const twice = (i: number) => trimmed[i] !== "" && trimmed.indexOf(trimmed[i]) !== i;
  const set = (i: number, code: string) => onChange(value.map((c, j) => (j === i ? code : c)));
  const remove = (i: number) => onChange(value.filter((_, j) => j !== i));
  const makeMain = (i: number) => onChange([value[i], ...value.filter((_, j) => j !== i)]);
  /** Adds a code, filling an empty row first; a code already listed is not added again. */
  const add = (code: string) => {
    if (trimmed.includes(code)) return;
    const empty = trimmed.indexOf("");
    onChange(empty >= 0 ? value.map((c, j) => (j === empty ? code : c)) : [...value, code]);
  };

  const generate = async () => {
    setGenerating(true);
    setProblem(null);
    try {
      // Ask for a few, in case codes generated earlier in this form aren't saved yet.
      const codes = await api<string[]>(`/offerings/barcodes/next?count=${Math.min(20, trimmed.length + 1)}`);
      const fresh = codes.find((c) => !trimmed.includes(c));
      if (fresh) add(fresh);
    } catch (e) {
      setProblem(e);
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="flex flex-col gap-1.5 text-sm sm:col-span-2">
      <span className="font-medium">{t("Barcodes")}</span>
      {value.length === 0 && <p className="text-xs text-muted">{t("No barcode yet. Add the maker's code, scan it, or generate one.")}</p>}
      <ul className="flex flex-col gap-2">
        {value.map((code, i) => (
          <li key={i} className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <Input
                aria-label={t("Barcode {n}", { n: i + 1 })}
                value={code}
                maxLength={64}
                invalid={twice(i)}
                onChange={(e) => set(i, e.target.value.replace(/\s/g, ""))}
                className="font-mono"
              />
              {i === 0 ? (
                <span className="shrink-0" title={t("Printed on labels")}>
                  <Badge tone="primary">{t("Main")}</Badge>
                </span>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => makeMain(i)}
                  aria-label={t("Make {code} the main barcode", { code: code || t("this") })}
                  title={t("Make main (printed on labels)")}
                >
                  <Star className="size-4" />
                </Button>
              )}
              <Button type="button" variant="ghost" size="sm" onClick={() => remove(i)} aria-label={t("Remove barcode {code}", { code })}>
                <Trash2 className="size-4" />
              </Button>
            </div>
            {twice(i) && <span className="text-xs text-danger">{t("Listed twice")}</span>}
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" size="sm" onClick={() => onChange([...value, ""])} disabled={value.length >= 20}>
          <Plus className="size-4" aria-hidden /> {t("Add barcode")}
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={() => setScanning(true)} disabled={value.length >= 20}>
          <ScanLine className="size-4" aria-hidden /> {t("Scan with camera")}
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={generate} loading={generating} disabled={value.length >= 20}>
          <Wand2 className="size-4" aria-hidden /> {t("Generate")}
        </Button>
      </div>
      <span className={cx("text-xs", error ? "text-danger" : "text-muted")}>
        {error
          ? translateServer(error)
          : t("Each barcode can belong to one entry only. Generated codes are in-store EAN-13 codes starting with 200.")}
      </span>
      <ErrorNote error={problem} />
      <CameraScanner
        open={scanning}
        onClose={() => setScanning(false)}
        onDetected={(code) => {
          add(code.trim());
          setScanning(false);
        }}
        title={t("Scan the barcode")}
      />
    </div>
  );
}
