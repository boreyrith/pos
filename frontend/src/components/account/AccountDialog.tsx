"use client";

import { LanguageSwitch } from "@/components/LanguageSwitch";
import { Modal } from "@/components/ui";
import { useUser } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { translateServer, useT } from "@/lib/i18n";

import { ChangePasswordForm } from "./ChangePasswordForm";


/** Your own account: details and password. */
export function AccountDialog({ onClose }: { onClose: () => void }) {
  const user = useUser();
  const t = useT();
  return (
    <Modal open onClose={onClose} title={t("My account")}>
      <dl className="mb-5 grid grid-cols-[110px_1fr] gap-x-4 gap-y-1.5 text-sm">
        <dt className="text-muted">{t("Name")}</dt>
        <dd className="font-medium">{user.fullName}</dd>
        <dt className="text-muted">{t("Username")}</dt>
        <dd>{user.username}</dd>
        <dt className="text-muted">{t("Role")}</dt>
        <dd>{translateServer(user.role.name)}</dd>
        <dt className="text-muted">{t("Branch")}</dt>
        <dd>{user.branchName}</dd>
        {user.email && (
          <>
            <dt className="text-muted">{t("Email")}</dt>
            <dd>{user.email}</dd>
          </>
        )}
        <dt className="text-muted">{t("Password set")}</dt>
        <dd>{user.passwordChangedAt ? dateTime(user.passwordChangedAt) : t("When the account was created")}</dd>
      </dl>
      <h3 className="mb-2 text-sm font-semibold">{t("Language")}</h3>
      <div className="mb-5">
        <LanguageSwitch />
        <p className="mt-1 text-xs text-muted">{t("Saved to your account, so it follows you to any device.")}</p>
      </div>
      <h3 className="mb-3 text-sm font-semibold">{t("Change password")}</h3>
      <ChangePasswordForm />
    </Modal>
  );
}
