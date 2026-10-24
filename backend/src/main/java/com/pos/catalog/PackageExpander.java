package com.pos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.common.ApiException;

/**
 * Works out what a package is made of, all the way down: the stock it uses (products and items in
 * base units, plus the parts of services inside it) and a flat list of contents for receipts.
 * Nested packages are expanded; their depth is limited and a package can't contain itself.
 * Collections are loaded lazily, so call this inside a transaction.
 */
public final class PackageExpander {

    public static final int MAX_DEPTH = 4;

    /** One thing in the package, for display: quantity in {@code unit} for the given number of packages. */
    public record Content(Offering offering, BigDecimal quantity, String unit, int depth) {
    }

    /**
     * {@code stock}: base units per stocked entry id, with the entries in {@code stocked}.
     * {@code contents}: everything inside, in display order.
     */
    public record Expansion(Map<Long, Integer> stock, Map<Long, Offering> stocked, List<Content> contents,
                            boolean containsServices) {
    }

    private PackageExpander() {
    }

    public static Expansion expand(Offering pkg, int packages) {
        Map<Long, Integer> stock = new LinkedHashMap<>();
        Map<Long, Offering> stocked = new LinkedHashMap<>();
        List<Content> contents = new ArrayList<>();
        boolean[] services = {false};
        List<Long> path = new ArrayList<>();
        path.add(pkg.getId());
        walk(pkg, BigDecimal.valueOf(packages), 0, path, stock, stocked, contents, services);
        return new Expansion(stock, stocked, contents, services[0]);
    }

    private static void walk(Offering pkg, BigDecimal multiplier, int depth, List<Long> path, Map<Long, Integer> stock,
                             Map<Long, Offering> stocked, List<Content> contents, boolean[] services) {
        if (depth >= MAX_DEPTH) {
            throw ApiException.badRequest("Packages can be nested at most " + MAX_DEPTH + " levels deep");
        }
        for (PackageLine line : pkg.getPackageLines()) {
            Offering c = line.getComponent();
            BigDecimal qty = line.getQuantity().multiply(multiplier);
            switch (c.getType()) {
                case PRODUCT, ITEM -> {
                    int factor = line.getUnit() == null ? 1 : UnitConversion.factorOf(c, line.getUnit().getId());
                    stock.merge(c.getId(), wholeUnits(qty.multiply(BigDecimal.valueOf(factor)), c), Integer::sum);
                    stocked.put(c.getId(), c);
                    contents.add(new Content(c, qty, line.getUnit() == null ? c.getUnitSymbol() : line.getUnit().getSymbol(), depth));
                }
                case SERVICE -> {
                    services[0] = true;
                    contents.add(new Content(c, qty, c.isHourly() ? "h" : null, depth));
                    // A service's parts are per job; hourly services use them once per package line.
                    BigDecimal jobs = c.isHourly() ? multiplier : qty;
                    for (ServiceComponent part : c.getComponents()) {
                        stock.merge(part.getItem().getId(),
                                wholeUnits(jobs.multiply(BigDecimal.valueOf(part.getQuantity())), part.getItem()), Integer::sum);
                        stocked.put(part.getItem().getId(), part.getItem());
                    }
                }
                case PACKAGE -> {
                    if (c.getId() != null && path.contains(c.getId())) {
                        throw ApiException.badRequest(c.getName() + " contains itself");
                    }
                    contents.add(new Content(c, qty, c.getUnitSymbol(), depth));
                    path.add(c.getId());
                    walk(c, qty, depth + 1, path, stock, stocked, contents, services);
                    path.removeLast();
                }
            }
        }
    }

    /** Stock is counted in whole base units, so 1.5 pcs can't be taken. */
    private static int wholeUnits(BigDecimal amount, Offering o) {
        BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        if (amount.compareTo(whole) != 0) {
            throw ApiException.badRequest(o.getName() + " would need " + amount.stripTrailingZeros().toPlainString() + " "
                    + o.getUnitSymbol() + ", but stock is counted in whole " + o.getUnitSymbol());
        }
        return whole.intValueExact();
    }

    /** How many packages the given stock can make; null if nothing in it is stocked. */
    public static Integer available(Map<Long, Integer> perPackage, Map<Long, Integer> stockOnHand) {
        Integer best = null;
        for (var e : perPackage.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int can = stockOnHand.getOrDefault(e.getKey(), 0) / e.getValue();
            best = best == null ? can : Math.min(best, can);
        }
        return best;
    }
}
