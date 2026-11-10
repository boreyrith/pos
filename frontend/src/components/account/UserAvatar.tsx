"use client";

import { useQuery } from "@tanstack/react-query";

import { cx } from "@/components/ui";
import { apiBlob } from "@/lib/api";
import { avatarFor } from "@/lib/avatars";
import type { User } from "@/lib/types";

/**
 * The signed-in person's picture: their photo (fetched with the session; it is never public), the
 * avatar they picked, or their initials.
 */
export function UserAvatar({ user, className }: { user: User; className?: string }) {
  const photo = useQuery({
    queryKey: ["me", "photo", user.id, user.photoVersion],
    queryFn: async () => dataUrl(await apiBlob(`/auth/me/photo?v=${user.photoVersion}`)),
    enabled: user.hasPhoto,
    staleTime: Infinity,
  });
  const url = user.hasPhoto ? photo.data : undefined;
  const avatar = user.hasPhoto ? undefined : avatarFor(user.avatar);
  if (avatar) {
    return <AvatarArt avatar={avatar} className={className} />;
  }
  const initials = user.fullName
    .split(" ")
    .map((part) => part[0])
    .slice(0, 2)
    .join("");
  return (
    <span
      className={cx(
        "grid shrink-0 place-items-center overflow-hidden rounded-full bg-linear-to-br from-primary to-primary-hover font-semibold text-on-primary",
        className ?? "size-9 text-xs",
      )}
    >
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element -- a local copy of the photo, sized by CSS
        <img src={url} alt="" className="size-full object-cover" />
      ) : (
        initials
      )}
    </span>
  );
}

/** A built-in avatar: the animal on its colours, sized like the photo (the emoji scales with the circle). */
export function AvatarArt({ avatar, className }: { avatar: NonNullable<ReturnType<typeof avatarFor>>; className?: string }) {
  return (
    <span
      className={cx("grid shrink-0 place-items-center overflow-hidden rounded-full", className ?? "size-9 text-xs")}
      style={{ backgroundImage: `linear-gradient(135deg, ${avatar.from}, ${avatar.to})` }}
      aria-hidden
    >
      <span className="text-[1.6em] leading-none">{avatar.emoji}</span>
    </span>
  );
}

/** The image as a data: URL, kept in the query cache (no blob: URL to release). */
function dataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}
