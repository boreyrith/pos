import { msg } from "./i18n";
import type { TokenResponse } from "./types";

/** An error response from the API, in RFC 9457 problem-detail form. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fieldErrors: Record<string, string> = {},
    /** A machine-readable reason, e.g. PASSWORD_CHANGE_REQUIRED. */
    readonly code?: string,
  ) {
    super(message);
  }
}

let accessToken: string | null = null;
let branchOverride: number | null = null;
let refreshing: Promise<TokenResponse | null> | null = null;
let onSessionEnded: () => void = () => {};

export function setAccessToken(token: string | null) {
  accessToken = token;
}

/** Admins only: the branch picked in the sidebar, sent so the server acts for that branch. */
export function setBranchOverride(branchId: number | null) {
  branchOverride = branchId;
}

export function onSessionExpired(handler: () => void) {
  onSessionEnded = handler;
}

/** The server couldn't be reached (restarting, offline); the session itself may be fine. */
export class ServerUnavailableError extends ApiError {
  constructor() {
    super(503, msg("Can't reach the POS server right now. Check the connection and try again in a moment."));
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Gets a new access token from the refresh cookie. Calls are shared within a tab, and serialised
 * across tabs with a Web Lock, because the server rotates the refresh token on every use and
 * treats a reused token as theft. Resolves null only when the server says the session is over
 * (401/403); if the server can't be reached it retries briefly, then throws
 * {@link ServerUnavailableError} so a restart or a network blip doesn't sign anyone out.
 */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshing) {
    const run = async () => {
      for (let attempt = 0; ; attempt++) {
        let res: Response | null = null;
        try {
          res = await fetch("/api/auth/refresh", { method: "POST", credentials: "same-origin" });
        } catch {
          // network error: retry below
        }
        if (res && (res.status === 401 || res.status === 403)) return null;
        if (res && res.ok) {
          const tokens = (await res.json()) as TokenResponse;
          accessToken = tokens.accessToken;
          return tokens;
        }
        if (attempt >= 3) throw new ServerUnavailableError();
        await sleep(1000 * (attempt + 1));
      }
    };
    const locked = (async (): Promise<TokenResponse | null> =>
      typeof navigator !== "undefined" && navigator.locks
        ? await navigator.locks.request("pos-refresh", run)
        : await run())();
    const pending = locked.finally(() => {
      refreshing = null;
    });
    refreshing = pending;
    return pending;
  }
  return refreshing;
}

async function toError(res: Response): Promise<ApiError> {
  let body: { detail?: string; title?: string; errors?: Record<string, string>; code?: string } = {};
  try {
    body = await res.json();
  } catch {
    // not JSON
  }
  const message =
    body.detail ?? body.title ?? (res.status === 403 ? msg("You do not have permission to do this") : `Request failed (${res.status})`);
  return new ApiError(res.status, message, body.errors ?? {}, body.code);
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const res = await request(path, init);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** Fetches a file (e.g. a CSV export) with the user's session and saves it as `filename`. */
export async function download(path: string, filename: string) {
  const blob = await (await request(path)).blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

async function request(path: string, init: RequestInit & { json?: unknown } = {}): Promise<Response> {
  const { json, ...rest } = init;
  const send = () => {
    const headers = new Headers(rest.headers);
    if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
    if (branchOverride !== null) headers.set("X-Branch-Id", String(branchOverride));
    if (json !== undefined) headers.set("Content-Type", "application/json");
    return fetch(`/api${path}`, {
      ...rest,
      headers,
      body: json !== undefined ? JSON.stringify(json) : rest.body,
      credentials: "same-origin",
    });
  };

  let res = await send();
  if (res.status === 401 && !path.startsWith("/auth/")) {
    // Throws ServerUnavailableError if the server can't be reached: the session isn't ended for that.
    const tokens = await refreshSession();
    if (!tokens) {
      onSessionEnded();
      throw new ApiError(401, msg("Your session has ended. Please sign in again."));
    }
    res = await send();
  }
  if (!res.ok) throw await toError(res);
  return res;
}

export function queryString(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}
