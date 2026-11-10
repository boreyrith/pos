import { msg } from "./i18n";

/**
 * The built-in avatars people can pick instead of a photo (My account). The codes match the server's
 * ProfilePhotoService.AVATARS and V35's CHECK; each is an animal on its own colours.
 */
export const AVATARS = [
  { code: "fox", emoji: "🦊", label: msg("Fox"), from: "#fdba74", to: "#f97316" },
  { code: "cat", emoji: "🐱", label: msg("Cat"), from: "#fde68a", to: "#f59e0b" },
  { code: "dog", emoji: "🐶", label: msg("Dog"), from: "#d6c4b0", to: "#a07856" },
  { code: "panda", emoji: "🐼", label: msg("Panda"), from: "#e5e7eb", to: "#9ca3af" },
  { code: "koala", emoji: "🐨", label: msg("Koala"), from: "#cbd5e1", to: "#64748b" },
  { code: "lion", emoji: "🦁", label: msg("Lion"), from: "#fcd34d", to: "#d97706" },
  { code: "tiger", emoji: "🐯", label: msg("Tiger"), from: "#fdba74", to: "#ea580c" },
  { code: "frog", emoji: "🐸", label: msg("Frog"), from: "#bbf7d0", to: "#22c55e" },
  { code: "owl", emoji: "🦉", label: msg("Owl"), from: "#ddd6fe", to: "#8b5cf6" },
  { code: "penguin", emoji: "🐧", label: msg("Penguin"), from: "#bae6fd", to: "#0ea5e9" },
  { code: "rabbit", emoji: "🐰", label: msg("Rabbit"), from: "#fbcfe8", to: "#ec4899" },
  { code: "bear", emoji: "🐻", label: msg("Bear"), from: "#e7c9a9", to: "#92603a" },
] as const;

export type AvatarCode = (typeof AVATARS)[number]["code"];

export const avatarFor = (code: string | null | undefined) => AVATARS.find((a) => a.code === code);
