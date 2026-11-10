import { describe, expect, it } from "vitest";

import { amountIn, money } from "./format";

describe("money", () => {
  it("shows the currency it is given", () => {
    expect(money(12.345, "USD")).toBe("$12.35");
    expect(money(50700, "KHR")).toContain("50,700");
    expect(money(50700, "KHR")).not.toContain("$");
  });

  it("shows no symbol while the store's currency is unknown, rather than guessing dollars", () => {
    expect(money(12.3, undefined)).toBe("12.30");
    expect(money(-1234.5, undefined)).toBe("-1,234.50");
  });
});

describe("amountIn", () => {
  it("puts the symbol after amounts other than dollars", () => {
    expect(amountIn(50700, { code: "KHR", symbol: "៛", decimals: 0 })).toBe("50,700 ៛");
    expect(amountIn(12.3, { code: "USD", symbol: "$", decimals: 2 })).toBe("$12.30");
  });
});
