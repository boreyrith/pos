import type { NextRequest } from "next/server";

/**
 * Forwards /api/* to the Spring Boot backend so the browser talks to one origin. This keeps the
 * HttpOnly refresh cookie first-party and lets API_URL be set when the server starts, not at build.
 */
const API_URL = process.env.API_URL ?? "http://localhost:8081";

const FORWARDED_REQUEST_HEADERS = ["accept", "authorization", "content-type", "cookie", "user-agent"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "cache-control"];

async function proxy(req: NextRequest, ctx: RouteContext<"/api/[...path]">) {
  const { path } = await ctx.params;
  const target = `${API_URL}/api/${path.map(encodeURIComponent).join("/")}${req.nextUrl.search}`;

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = req.headers.get(name);
    if (value) headers.set(name, value);
  }
  const forwardedFor = req.headers.get("x-forwarded-for");
  if (forwardedFor) headers.set("x-forwarded-for", forwardedFor);

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers,
      body: req.method === "GET" || req.method === "HEAD" ? undefined : await req.arrayBuffer(),
      redirect: "manual",
      cache: "no-store",
    });
  } catch {
    return Response.json(
      { status: 502, title: "Bad Gateway", detail: "The POS server is not reachable. Is the backend running?" },
      { status: 502 },
    );
  }

  const out = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) out.set(name, value);
  }
  for (const cookie of upstream.headers.getSetCookie()) out.append("set-cookie", cookie);

  const noBody = upstream.status === 204 || upstream.status === 304 || req.method === "HEAD";
  return new Response(noBody ? null : upstream.body, { status: upstream.status, headers: out });
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
