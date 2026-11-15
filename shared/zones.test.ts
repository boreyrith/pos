import { afterEach, describe, expect, it, vi } from "vitest";

/** zones.ts reads the app it runs in (NEXT_PUBLIC_ZONE) when it loads, so each case loads it afresh. */
async function zonesIn(zone: string | undefined) {
  vi.resetModules();
  vi.stubEnv("NEXT_PUBLIC_ZONE", zone);
  return import("./zones");
}

afterEach(() => vi.unstubAllEnvs());

describe("zones", () => {
  it("knows each app and its prefix, and defaults to administration", async () => {
    expect((await zonesIn("buy")).BASE_PATH).toBe("/buy");
    expect((await zonesIn("nav")).BASE_PATH).toBe("");
    expect((await zonesIn("app")).ZONE).toBe("app");
    expect((await zonesIn(undefined)).ZONE).toBe("ctl");
    expect((await zonesIn("other")).ZONE).toBe("ctl");
  });

  it("signs in as the till only in the till; every other app as administration", async () => {
    expect((await zonesIn("cas")).PLATFORM).toBe("CAS");
    for (const z of ["ctl", "buy", "app", "nav"]) expect((await zonesIn(z)).PLATFORM).toBe("CTL");
  });

  it("sends the till's pages to the till and the rest to administration, whole path segments only", async () => {
    const { zoneOf } = await zonesIn("ctl");
    expect(zoneOf("/pos")).toBe("cas");
    expect(zoneOf("/orders/12?tab=items")).toBe("cas");
    expect(zoneOf("/online-orders#new")).toBe("cas");
    expect(zoneOf("/catalog")).toBe("ctl");
    expect(zoneOf("/ordersheet")).toBe("ctl");
    expect(zoneOf("/position")).toBe("ctl");
  });

  it("links within the app as they are and to the other app with its prefix", async () => {
    const ctl = await zonesIn("ctl");
    expect(ctl.hrefFor("/catalog")).toBe("/catalog");
    expect(ctl.hrefFor("/orders/3")).toBe("/cas/orders/3");
    const cas = await zonesIn("cas");
    expect(cas.hrefFor("/orders/3")).toBe("/orders/3");
    expect(cas.hrefFor("/settings")).toBe("/ctl/settings");
  });

  it("goes to a page of this app by the router, and of the other app by a page load", async () => {
    const { goTo } = await zonesIn("cas");
    const router = { replace: vi.fn() };
    const replace = vi.fn();
    vi.stubGlobal("window", { location: { replace } });
    goTo("/pos", router);
    goTo("/dashboard", router);
    expect(router.replace).toHaveBeenCalledWith("/pos");
    expect(replace).toHaveBeenCalledWith("/ctl/dashboard");
    vi.unstubAllGlobals();
  });
});
