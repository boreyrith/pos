"use client";

import { ArrowBigUp, Eye, EyeOff, Loader2 } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useId, useRef, useState, useSyncExternalStore } from "react";

import { homePath } from "@/components/AppShell";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { cx, ErrorNote } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useT } from "@/lib/i18n";

import { BrandHero } from "./BrandPanel";

/**
 * "Remember me" on this device, off until someone ticks it: the choice and the username, never the password (the browser's own
 * password manager offers to keep that, safely). Off, for a shared till: nothing is kept and closing
 * the browser signs out.
 */
const REMEMBER_KEY = "pos.login";

type Remembered = { remember: boolean; username: string };

function readRemembered(): string {
  try {
    return localStorage.getItem(REMEMBER_KEY) ?? "";
  } catch {
    return "";
  }
}

function parseRemembered(raw: string): Remembered {
  try {
    const v = JSON.parse(raw) as Partial<Remembered>;
    return { remember: v.remember === true, username: v.remember === true ? (v.username ?? "") : "" };
  } catch {
    // Nothing saved yet (or unreadable): not remembered.
    return { remember: false, username: "" };
  }
}

function saveRemembered(v: Remembered) {
  try {
    localStorage.setItem(REMEMBER_KEY, JSON.stringify(v.remember ? v : { remember: false }));
  } catch {
    // Private windows and blocked storage: nothing is remembered, which is fine.
  }
}

const noSubscribe = () => () => {};

export function LoginForm() {
  const { state, login } = useAuth();
  const t = useT();
  const router = useRouter();
  const params = useSearchParams();
  const next = params.get("next");
  // Only follow same-site paths, never a full URL from the query string.
  // Otherwise each role's own start page (the dashboard, the till, or their work).
  const asked = next && next.startsWith("/") && !next.startsWith("//") ? next : null;

  // What this device remembers (empty while the page is rendered on the server).
  const saved = parseRemembered(useSyncExternalStore(noSubscribe, readRemembered, () => ""));
  // null: not typed yet, so the remembered username shows.
  const [typedUsername, setUsername] = useState<string | null>(null);
  const [rememberChoice, setRemember] = useState<boolean | null>(null);
  const username = typedUsername ?? saved.username;
  const remember = rememberChoice ?? saved.remember;
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const fieldsRef = useRef<HTMLDivElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (state.status === "signed-in") router.replace(asked ?? homePath(state.user));
  }, [state, router, asked]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password, remember);
      saveRemembered({ remember, username: username.trim() });
    } catch (err) {
      setError(err);
      setSubmitting(false);
      shake(fieldsRef.current);
      // Ready to retype: the next keystroke replaces the wrong password.
      passwordRef.current?.focus();
      passwordRef.current?.select();
    }
  }

  const busy = submitting || state.status === "loading";

  return (
    <section className="flex min-h-dvh flex-col lg:px-10 lg:py-6">
      {/* Tablets and phones: a light brand band on top, holding the language switch. */}
      <BrandHero>
        <LanguageSwitch segmented />
      </BrandHero>
      <div className="hidden justify-end lg:flex">
        <LanguageSwitch segmented />
      </div>

      <div className="flex flex-1 justify-center px-5 pt-8 pb-10 md:px-8 md:pt-0 lg:items-center lg:py-10">
        {/* Phones: the form under the band. Tablets: a card overlapping it. Desktop: plain, in its column. */}
        <div
          className={cx(
            "relative h-fit w-full max-w-[400px] animate-[rise-in_0.6s_cubic-bezier(0.2,0.8,0.2,1)_both] motion-reduce:animate-none",
            "md:-mt-20 md:max-w-[460px] md:rounded-[28px] md:border md:border-border md:bg-surface md:p-10 md:shadow-[0_24px_60px_-20px_rgb(0_0_0/0.35)]",
            "lg:mt-0 lg:max-w-[380px] lg:rounded-none lg:border-0 lg:bg-transparent lg:p-0 lg:shadow-none",
          )}
        >
          <h1 className="text-[32px] leading-tight font-semibold tracking-tight">{t("Sign in")}</h1>
          <p className="mt-2 text-[15px] text-muted">{t("Use your staff account")}</p>

          {/* A real sign-in form (method, names, autocomplete), so the browser's password manager offers to save and fill it. */}
          <form onSubmit={onSubmit} method="post" action="/login" className="mt-9 flex flex-col gap-4">
            {/* Username and password as one grouped list, as in iOS Settings. */}
            <div
              ref={fieldsRef}
              className={cx(
                "overflow-hidden rounded-2xl border bg-surface transition-[border-color,box-shadow] duration-200",
                "focus-within:border-primary focus-within:ring-4 focus-within:ring-primary/15",
                error ? "border-danger" : "border-border",
              )}
            >
              <FloatingInput
                label={t("Username")}
                name="username"
                autoFocus={!saved.username}
                autoComplete="username"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
              />
              <div className="ml-4 h-px bg-border" />
              <FloatingInput
                ref={passwordRef}
                label={t("Password")}
                name="password"
                autoFocus={!!saved.username}
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => setCapsLock(e.getModifierState("CapsLock"))}
                onKeyUp={(e) => setCapsLock(e.getModifierState("CapsLock"))}
                onBlur={() => setCapsLock(false)}
                required
                trailing={
                  <button
                    type="button"
                    onClick={() => setShowPassword((s) => !s)}
                    aria-label={showPassword ? t("Hide password") : t("Show password")}
                    aria-pressed={showPassword}
                    className="grid size-10 place-items-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:outline-2 focus-visible:outline-primary"
                  >
                    {showPassword ? <EyeOff className="size-[18px]" aria-hidden /> : <Eye className="size-[18px]" aria-hidden />}
                  </button>
                }
              />
            </div>

            <label className="flex items-start gap-2.5 px-1 text-[15px]">
              <input
                type="checkbox"
                name="remember"
                checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
                className="mt-1 size-4 shrink-0 accent-primary"
              />
              <span>
                {t("Remember me")}
                <span className="block text-[13px] text-muted">{t("Keeps your username and keeps you signed in on this device. Turn it off on a shared till.")}</span>
              </span>
            </label>

            {capsLock && (
              <p className="-mt-1 flex items-center gap-1.5 px-1 text-[13px] text-warning" role="status">
                <ArrowBigUp className="size-4" aria-hidden />
                {t("Caps Lock is on")}
              </p>
            )}

            <ErrorNote error={error} />

            <button
              type="submit"
              disabled={busy}
              className={cx(
                "mt-2 inline-flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary text-[17px] font-medium text-on-primary",
                "shadow-sm shadow-primary/30 transition-[background-color,transform] duration-150 hover:bg-primary-hover active:scale-[0.985]",
                "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary",
                "disabled:cursor-not-allowed disabled:opacity-60 disabled:active:scale-100",
              )}
            >
              {busy && <Loader2 className="size-[18px] animate-spin" aria-hidden />}
              {t("Sign in")}
            </button>
          </form>
        </div>
      </div>
    </section>
  );
}

/** A borderless input whose label sits inside it and moves up once there is text, as on Apple's sign-in. */
function FloatingInput({
  label,
  trailing,
  ref,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  trailing?: React.ReactNode;
  ref?: React.Ref<HTMLInputElement>;
}) {
  const id = useId();
  return (
    <div className="relative">
      <input
        ref={ref}
        id={id}
        placeholder=" "
        className={cx(
          "peer h-14 w-full bg-transparent px-4 pt-5 pb-1 text-[17px] text-text outline-none",
          // Keep browser autofill from painting its own background over the field.
          "autofill:shadow-[inset_0_0_0_1000px_var(--surface)] autofill:[-webkit-text-fill-color:var(--text)]",
          trailing != null && "pr-12",
        )}
        {...props}
      />
      <label
        htmlFor={id}
        className={cx(
          "pointer-events-none absolute top-1/2 left-4 origin-left -translate-y-1/2 text-[17px] text-muted transition-all duration-150",
          "peer-focus:top-2 peer-focus:translate-y-0 peer-focus:text-xs",
          "peer-[:not(:placeholder-shown)]:top-2 peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-xs",
          "peer-autofill:top-2 peer-autofill:translate-y-0 peer-autofill:text-xs",
        )}
      >
        {label}
      </label>
      {trailing && <div className="absolute top-1/2 right-2 -translate-y-1/2">{trailing}</div>}
    </div>
  );
}

/** A short side-to-side shake, the macOS "wrong password" cue. Skipped when the user prefers less motion. */
function shake(el: HTMLElement | null) {
  if (!el || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  el.animate(
    [
      { transform: "translateX(0)" },
      { transform: "translateX(-8px)" },
      { transform: "translateX(7px)" },
      { transform: "translateX(-5px)" },
      { transform: "translateX(3px)" },
      { transform: "translateX(0)" },
    ],
    { duration: 420, easing: "cubic-bezier(0.36, 0.07, 0.19, 0.97)" },
  );
}
