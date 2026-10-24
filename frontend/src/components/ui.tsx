"use client";

import { Loader2, X } from "lucide-react";
import { forwardRef, useEffect, useRef } from "react";

import { translateServer, useLang, useT } from "@/lib/i18n";

export function cx(...classes: (string | false | null | undefined)[]) {
  return classes.filter(Boolean).join(" ");
}

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";

const buttonStyles: Record<ButtonVariant, string> = {
  primary: "bg-primary text-on-primary hover:bg-primary-hover",
  secondary: "bg-surface text-text border border-border hover:bg-surface-2",
  ghost: "text-text hover:bg-surface-2",
  danger: "bg-danger text-white hover:opacity-90",
};

export const Button = forwardRef<
  HTMLButtonElement,
  React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: "sm" | "md" | "lg"; loading?: boolean }
>(function Button({ variant = "primary", size = "md", loading, disabled, className, children, ...props }, ref) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={cx(
        "inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary",
        "disabled:cursor-not-allowed disabled:opacity-50",
        size === "sm" && "h-8 px-3 text-sm",
        size === "md" && "h-10 px-4 text-sm",
        size === "lg" && "h-14 px-6 text-base",
        buttonStyles[variant],
        className,
      )}
      {...props}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
});

export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ className, invalid, ...props }, ref) {
    return (
      <input
        ref={ref}
        aria-invalid={invalid || undefined}
        className={cx(
          "h-10 w-full rounded-lg border bg-surface px-3 text-sm text-text placeholder:text-muted",
          "focus:outline-2 focus:outline-offset-0 focus:outline-primary",
          invalid ? "border-danger" : "border-border",
          className,
        )}
        {...props}
      />
    );
  },
);

export function Select({ className, ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cx(
        "h-10 rounded-lg border border-border bg-surface px-3 text-sm text-text",
        "focus:outline-2 focus:outline-primary",
        className,
      )}
      {...props}
    />
  );
}

export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5 text-sm">
      <span className="font-medium">{label}</span>
      {children}
      {error ? <span className="text-xs text-danger">{translateServer(error)}</span> : hint && <span className="text-xs text-muted">{hint}</span>}
    </label>
  );
}

export function Card({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cx("rounded-xl border border-border bg-surface", className)} {...props} />;
}

const badgeTones = {
  neutral: "bg-surface-2 text-muted border border-border",
  primary: "bg-primary-soft text-primary",
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  danger: "bg-danger-soft text-danger",
};

export function Badge({ tone = "neutral", children }: { tone?: keyof typeof badgeTones; children: React.ReactNode }) {
  return (
    <span className={cx("inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap", badgeTones[tone])}>
      {children}
    </span>
  );
}

export function Spinner({ label }: { label?: string }) {
  const t = useT();
  return (
    <div className="flex items-center justify-center gap-2 p-8 text-sm text-muted" role="status">
      <Loader2 className="size-4 animate-spin" aria-hidden />
      {label ?? t("Loading")}
    </div>
  );
}

/** Shows an error; messages from the server are shown in the user's language. */
export function ErrorNote({ error }: { error: unknown }) {
  const t = useT();
  useLang(); // re-render server messages when the language changes
  if (!error) return null;
  const message = error instanceof Error ? translateServer(error.message) : t("Something went wrong");
  return (
    <div role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
      {message}
    </div>
  );
}

export function PageHeader({ title, actions }: { title: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
      <h1 className="text-xl font-semibold">{title}</h1>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** A modal built on the native <dialog> element, which handles focus trapping and Escape. */
export function Modal({
  open,
  onClose,
  title,
  children,
  wide,
  dismissable = true,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  wide?: boolean;
  dismissable?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const t = useT();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onCancel={(e) => {
        e.preventDefault();
        if (dismissable) onClose();
      }}
      className={cx(
        "m-auto w-[calc(100%-2rem)] rounded-2xl border border-border bg-surface p-0 text-text shadow-xl backdrop:bg-black/40",
        wide ? "max-w-2xl" : "max-w-md",
      )}
    >
      {open && (
        <div className="flex max-h-[90vh] flex-col">
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 className="text-base font-semibold">{title}</h2>
            {dismissable && (
              <button onClick={onClose} className="rounded-md p-1 text-muted hover:bg-surface-2" aria-label={t("Close")}>
                <X className="size-5" />
              </button>
            )}
          </div>
          <div className="overflow-y-auto px-5 py-4">{children}</div>
        </div>
      )}
    </dialog>
  );
}

export function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  const t = useT();
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-end gap-2 pt-4 text-sm">
      <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => onChange(page - 1)}>
        {t("Previous")}
      </Button>
      <span className="text-muted">{t("Page {page} of {total}", { page: page + 1, total: totalPages })}</span>
      <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        {t("Next")}
      </Button>
    </div>
  );
}
