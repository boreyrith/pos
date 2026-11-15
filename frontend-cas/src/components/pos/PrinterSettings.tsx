"use client";

import { Printer } from "lucide-react";
import { useState } from "react";

import { Button, Field, Modal, Select, Spinner } from "@pos/shared/components/ui";
import { useStoreSettings } from "@pos/shared/api/queries";
import { useT } from "@pos/shared/i18n";

import { PrintSheet, TestReceipt } from "@/components/pos/Receipt";
import { MAX_COPIES, printSheet, savePrinter, usePrinter } from "@/lib/printer";

/** The top bar's printer button: this till's receipt printing (lib/printer). */
export function PrinterButton() {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        onClick={() => setOpen(true)}
        title={t("Receipt printer")}
        aria-label={t("Receipt printer")}
        className="flex h-10 items-center rounded-xl px-2.5 text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:outline-2 focus-visible:outline-primary"
      >
        <Printer className="size-[18px]" aria-hidden />
      </button>
      {open && <PrinterDialog onClose={() => setOpen(false)} />}
    </>
  );
}

function PrinterDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const store = useStoreSettings().data;
  const printer = usePrinter();

  return (
    <Modal open onClose={onClose} title={t("Receipt printer")} wide>
      <div className="grid gap-5 sm:grid-cols-[1fr_240px]">
        <div className="flex flex-col gap-4 text-sm">
          <p className="text-muted">
            {t("Receipts print through this browser to the printer installed on this computer. These settings are for this till only.")}
          </p>
          <label className="flex items-start gap-3">
            <input
              type="checkbox"
              className="mt-0.5 size-4"
              checked={printer.autoPrint}
              onChange={(e) => savePrinter({ ...printer, autoPrint: e.target.checked })}
            />
            <span>
              <span className="font-medium">{t("Print the receipt when a sale is paid")}</span>
              <span className="block text-muted">{t("Otherwise, tap Print receipt after the sale.")}</span>
            </span>
          </label>
          <Field label={t("Copies of each sale's receipt")} hint={t("Reprints from Sales print one.")}>
            <Select value={printer.copies} onChange={(e) => savePrinter({ ...printer, copies: Number(e.target.value) })}>
              {Array.from({ length: MAX_COPIES }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>{n === 1 ? t("1 copy") : t("{n} copies", { n })}</option>
              ))}
            </Select>
          </Field>
          {store && (
            <p className="text-muted">
              {t("Paper: {width} mm, for the whole store (Settings > Company in administration).", { width: store.receiptWidth })}
            </p>
          )}
          <div className="rounded-xl bg-surface-2 p-3">
            <div className="mb-1 font-medium">{t("Setting up the printer")}</div>
            <ol className="list-decimal space-y-1 pl-5 text-muted">
              <li>{t("Install the printer's driver on this computer (USB or network) and make it the default printer.")}</li>
              <li>{t("In the driver, choose the paper size of the roll ({width} mm) and no margins.", { width: store?.receiptWidth ?? 80 })}</li>
              <li>{t("Print a test page. In the print window, turn off Headers and footers and set Margins to None or Default.")}</li>
              <li>{t("To print without the print window, start Chrome with --kiosk-printing (add it to the till's Chrome shortcut): receipts then go straight to the default printer.")}</li>
            </ol>
          </div>
        </div>
        <div className="flex flex-col gap-3">
          {store ? (
            <>
              <div className="rounded-lg border border-border">
                <TestReceipt store={store} />
              </div>
              <PrintSheet id="printer-test">
                <TestReceipt store={store} />
              </PrintSheet>
            </>
          ) : (
            <Spinner />
          )}
          <Button variant="secondary" onClick={() => void printSheet("printer-test")} disabled={!store}>
            <Printer className="size-4" aria-hidden /> {t("Print a test page")}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
