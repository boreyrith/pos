"use client";

import { useMutation } from "@tanstack/react-query";
import { ArrowLeft, Camera, Check, ImageUp, KeyRound, LogOut, MonitorSmartphone, Pencil, UserRound } from "lucide-react";
import { useRef, useState } from "react";

import { LanguageSwitch } from "@/components/LanguageSwitch";
import { Button, ErrorNote, Modal, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth, useUser } from "@/lib/auth";
import { AVATARS } from "@/lib/avatars";
import { dateTime, longDate } from "@/lib/format";
import { msg, translateServer, useT } from "@/lib/i18n";
import type { User } from "@/lib/types";

import { ChangePasswordForm } from "./ChangePasswordForm";
import { MySessions } from "./MySessions";
import { GENDER_LABELS, PersonalDetailsFields, detailsForm, detailsJson } from "./PersonalDetailsFields";
import { AvatarArt, UserAvatar } from "./UserAvatar";

const TABS = [
  { key: "profile", label: msg("Profile"), icon: UserRound },
  { key: "devices", label: msg("Devices"), icon: MonitorSmartphone },
  { key: "password", label: msg("Password"), icon: KeyRound },
] as const;

/**
 * Your own account, with Sign out at the top, then tabs: who you are and language; your signed-in devices; your
 * password. Your picture (optional: a photo, a built-in avatar or your initials) changes from the
 * picture at the top.
 */
export function AccountDialog({ onClose, onSignOut }: { onClose: () => void; onSignOut: () => void }) {
  const user = useUser();
  const t = useT();
  const [tab, setTab] = useState<(typeof TABS)[number]["key"]>("profile");
  const [picking, setPicking] = useState(false);

  return (
    <Modal open onClose={onClose} title={t("My account")}>
      {/* Who is signed in, always in view. */}
      <div className="mb-4 flex items-center gap-3">
        <button
          type="button"
          onClick={() => setPicking(!picking)}
          aria-label={t("Change picture")}
          title={t("Change picture")}
          aria-expanded={picking}
          className="group relative shrink-0 rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          <UserAvatar user={user} className="size-14 text-base" />
          <span className="absolute -right-0.5 -bottom-0.5 grid size-6 place-items-center rounded-full border-2 border-surface bg-primary text-on-primary shadow-sm transition-transform group-hover:scale-110">
            <Camera className="size-3" aria-hidden />
          </span>
        </button>
        <div className="min-w-0 flex-1">
          <div className="truncate font-semibold">{user.fullName}</div>
          <div className="truncate text-sm text-muted">
            {translateServer(user.role.name)} · {user.branchName}
          </div>
        </div>
        {/* Always at hand, whichever tab is open. */}
        <Button variant="secondary" size="sm" onClick={onSignOut} className="shrink-0 whitespace-nowrap">
          <LogOut className="size-4" aria-hidden />
          {t("Sign out")}
        </Button>
      </div>

      <div className="mb-4 flex gap-1 border-b border-border" role="tablist" aria-label={t("My account")}>
        {TABS.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={!picking && tab === x.key}
            onClick={() => {
              setTab(x.key);
              setPicking(false);
            }}
            className={cx(
              "-mb-px flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium",
              !picking && tab === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            <x.icon className="size-4" aria-hidden />
            {t(x.label)}
          </button>
        ))}
      </div>

      {/* Every tab gets the same room, so the dialog keeps its size; longer content scrolls inside. */}
      <div className="h-[24rem] overflow-y-auto">
        {picking && <PicturePicker user={user} onDone={() => setPicking(false)} />}
        {!picking && tab === "profile" && (
          <div role="tabpanel" className="flex flex-col gap-5">
            <MyDetails user={user} />
            <div>
              <h3 className="mb-2 text-sm font-semibold">{t("Language")}</h3>
              <LanguageSwitch />
              <p className="mt-1 text-xs text-muted">{t("Saved to your account, so it follows you to any device.")}</p>
            </div>
          </div>
        )}

        {!picking && tab === "devices" && (
          <div role="tabpanel">
            <p className="text-sm text-muted">{t("Sign out any device you don't recognise, or have left signed in.")}</p>
            <MySessions />
          </div>
        )}

        {!picking && tab === "password" && (
          <div role="tabpanel">
            <p className="mb-4 text-sm text-muted">
              {t("Password set")}: {user.passwordChangedAt ? dateTime(user.passwordChangedAt) : t("When the account was created")}
            </p>
            <ChangePasswordForm />
          </div>
        )}
      </div>
    </Modal>
  );
}

/**
 * Choosing your picture, all optional: upload a photo (PNG, JPEG or WebP up to 2 MB), pick a
 * built-in avatar, or use your initials. One at a time; the server replaces the other.
 */
function PicturePicker({ user, onDone }: { user: User; onDone: () => void }) {
  const t = useT();
  const { updateUser } = useAuth();
  const fileRef = useRef<HTMLInputElement>(null);
  const upload = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("file", file);
      return api<User>("/auth/me/photo", { method: "PUT", body });
    },
    onSuccess: updateUser,
  });
  const choose = useMutation({
    mutationFn: (avatar: string | null) => api<User>("/auth/me/avatar", { method: "PUT", json: { avatar } }),
    onSuccess: updateUser,
  });
  const busy = upload.isPending || choose.isPending;
  const tile = (selected: boolean) =>
    cx(
      "relative grid aspect-square place-items-center rounded-full transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-60",
      selected ? "ring-3 ring-primary ring-offset-2 ring-offset-surface" : "hover:scale-105",
    );
  const tick = (
    <span className="absolute -right-0.5 -bottom-0.5 grid size-5 place-items-center rounded-full border-2 border-surface bg-primary text-on-primary">
      <Check className="size-3" aria-hidden />
    </span>
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{t("Choose a picture")}</h3>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          <ArrowLeft className="size-4" aria-hidden /> {t("Back")}
        </Button>
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-xl border border-border p-3">
        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) upload.mutate(file);
            e.target.value = "";
          }}
        />
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => fileRef.current?.click()}
          loading={upload.isPending}
          disabled={busy}
          className="shrink-0 whitespace-nowrap"
        >
          <ImageUp className="size-4" aria-hidden /> {user.hasPhoto ? t("Replace photo") : t("Upload photo")}
        </Button>
        <span className="min-w-0 text-xs text-muted">{t("PNG, JPEG or WebP, up to 2 MB.")}</span>
      </div>

      <div>
        <p className="mb-2 text-xs text-muted">{t("Or pick an avatar")}</p>
        <div className="grid grid-cols-5 gap-3 sm:grid-cols-7">
          <button
            type="button"
            onClick={() => choose.mutate(null)}
            disabled={busy}
            aria-label={t("Your initials")}
            title={t("Your initials")}
            aria-pressed={!user.hasPhoto && !user.avatar}
            className={tile(!user.hasPhoto && !user.avatar)}
          >
            <span className="grid size-full place-items-center rounded-full bg-linear-to-br from-primary to-primary-hover text-sm font-semibold text-on-primary">
              {user.fullName
                .split(" ")
                .map((part) => part[0])
                .slice(0, 2)
                .join("")}
            </span>
            {!user.hasPhoto && !user.avatar && tick}
          </button>
          {AVATARS.map((a) => {
            const selected = !user.hasPhoto && user.avatar === a.code;
            return (
              <button
                key={a.code}
                type="button"
                onClick={() => choose.mutate(a.code)}
                disabled={busy}
                aria-label={t(a.label)}
                title={t(a.label)}
                aria-pressed={selected}
                className={tile(selected)}
              >
                <AvatarArt avatar={a} className="size-full text-xl" />
                {selected && tick}
              </button>
            );
          })}
        </div>
      </div>
      <ErrorNote error={upload.error ?? choose.error} />
    </div>
  );
}

/**
 * Your details: the account (display name, username, role and branch, set by user admins) and your
 * personal details, which you can edit here.
 */
function MyDetails({ user }: { user: User }) {
  const t = useT();
  const { updateUser } = useAuth();
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState(() => detailsForm(user));
  const save = useMutation({
    mutationFn: () => api<User>("/auth/me/details", { method: "PUT", json: detailsJson(form) }),
    onSuccess: (u) => {
      updateUser(u);
      setEditing(false);
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const none = <span className="text-muted">{t("Not given")}</span>;
  const row = (label: string, value: React.ReactNode) => (
    <>
      <dt className="text-muted">{t(label)}</dt>
      <dd className="min-w-0 truncate">{value}</dd>
    </>
  );

  if (editing) {
    return (
      <form
        className="grid grid-cols-2 gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <h3 className="col-span-2 text-sm font-semibold">{t("Personal details")}</h3>
        <PersonalDetailsFields form={form} onChange={setForm} errors={errors} />
        <p className="col-span-2 text-xs text-muted">{t("Your display name, role and branch are changed by a user admin.")}</p>
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => {
              setForm(detailsForm(user));
              save.reset();
              setEditing(false);
            }}
          >
            {t("Cancel")}
          </Button>
          <Button type="submit" size="sm" loading={save.isPending}>
            {t("Save")}
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <dl className="grid grid-cols-[120px_1fr] gap-x-4 gap-y-2 text-sm">
        {row(msg("Display name"), user.fullName)}
        {row(msg("Username"), <span className="font-mono">{user.username}</span>)}
        {row(msg("Role"), translateServer(user.role.name))}
        {row(msg("Branch"), user.branchName)}
      </dl>
      <div className="flex items-center justify-between gap-2 border-t border-border pt-3">
        <h3 className="text-sm font-semibold">{t("Personal details")}</h3>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            setForm(detailsForm(user));
            setEditing(true);
          }}
        >
          <Pencil className="size-4" aria-hidden /> {t("Edit")}
        </Button>
      </div>
      <dl className="grid grid-cols-[120px_1fr] gap-x-4 gap-y-2 text-sm">
        {row(msg("Khmer name"), user.nameKm ? <span lang="km">{user.nameKm}</span> : none)}
        {row(msg("Latin name"), user.nameLatin ?? none)}
        {row(msg("Gender"), user.gender ? t(GENDER_LABELS[user.gender]) : none)}
        {row(msg("Date of birth"), user.dateOfBirth ? longDate(user.dateOfBirth) : none)}
        {row(msg("Phone"), user.phone ?? none)}
        {row(msg("Email"), user.email ?? none)}
      </dl>
    </div>
  );
}
