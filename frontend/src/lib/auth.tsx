"use client";

import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { ServerUnavailableError, api, onSessionExpired, refreshSession, setAccessToken } from "./api";
import { type Lang, getLang, setLang } from "./i18n";
import type { Permission, TokenResponse, User } from "./types";

type AuthState =
  | { status: "loading"; user: null }
  | { status: "signed-out"; user: null }
  | { status: "signed-in"; user: User };

interface AuthContextValue {
  state: AuthState;
  login: (username: string, password: string) => Promise<User>;
  logout: () => Promise<void>;
  /** Changes your own password; the server renews this session. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  /** Switches the interface language now and saves it to your account. */
  changeLanguage: (lang: Lang) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading", user: null });
  const queryClient = useQueryClient();

  const signOutLocally = useCallback(() => {
    setAccessToken(null);
    queryClient.clear();
    setState({ status: "signed-out", user: null });
  }, [queryClient]);

  useEffect(() => {
    onSessionExpired(signOutLocally);
    let cancelled = false;
    // Resume the session from the refresh cookie. If the server is unreachable (e.g. restarting),
    // keep trying instead of sending the person to the sign-in page.
    const resume = async () => {
      for (;;) {
        try {
          const tokens = await refreshSession();
          // A resumed session follows the language saved on the account.
          if (tokens && !cancelled) setLang(tokens.user.language);
          if (!cancelled) setState(tokens ? { status: "signed-in", user: tokens.user } : { status: "signed-out", user: null });
          return;
        } catch (e) {
          if (!(e instanceof ServerUnavailableError) || cancelled) {
            if (!cancelled) setState({ status: "signed-out", user: null });
            return;
          }
          await new Promise((resolve) => setTimeout(resolve, 3000));
        }
      }
    };
    resume();
    return () => {
      cancelled = true;
    };
  }, [signOutLocally]);

  const login = useCallback(async (username: string, password: string) => {
    const tokens = await api<TokenResponse>("/auth/login", { method: "POST", json: { username, password } });
    setAccessToken(tokens.accessToken);
    let user = tokens.user;
    // The language picked on the sign-in page is what this person wants: save it to the account.
    if (getLang() !== user.language) {
      user = await api<User>("/auth/me/language", { method: "PUT", json: { language: getLang() } }).catch(() => user);
    }
    setState({ status: "signed-in", user });
    return user;
  }, []);

  const changeLanguage = useCallback(async (lang: Lang) => {
    setLang(lang);
    const user = await api<User>("/auth/me/language", { method: "PUT", json: { language: lang } });
    setState((s) => (s.status === "signed-in" ? { status: "signed-in", user: { ...s.user, language: user.language } } : s));
  }, []);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const tokens = await api<TokenResponse>("/auth/password", { method: "POST", json: { currentPassword, newPassword } });
    setAccessToken(tokens.accessToken);
    setState({ status: "signed-in", user: tokens.user });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api<void>("/auth/logout", { method: "POST" });
    } finally {
      signOutLocally();
    }
  }, [signOutLocally]);

  const value = useMemo(
    () => ({ state, login, logout, changePassword, changeLanguage }),
    [state, login, logout, changePassword, changeLanguage],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}

/** The signed-in user. Only use below the (app) layout, which guarantees one. */
export function useUser(): User {
  const { state } = useAuth();
  if (state.status !== "signed-in") throw new Error("No signed-in user");
  return state.user;
}

/** Whether the signed-in user has a permission. The server checks every request too; this only shapes the screens. */
export function useCan(): (...any: Permission[]) => boolean {
  const user = useUser();
  return useCallback((...any: Permission[]) => any.some((p) => user.permissions.includes(p)), [user]);
}

export const hasPermission = (user: User, ...any: Permission[]) => any.some((p) => user.permissions.includes(p));
