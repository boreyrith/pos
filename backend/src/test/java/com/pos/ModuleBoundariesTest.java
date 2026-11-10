package com.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import jakarta.persistence.Table;

/**
 * The backend is one application made of modules. A module is one or more packages under
 * {@code com.pos}; {@link #LAYERS} lists them from the bottom up, and a module may use only the
 * modules below it. That keeps the dependencies one-way, so a module can be understood, tested and,
 * if it is ever worth it, moved out on its own.
 *
 * <p>{@link #KNOWN_UPWARD} lists the dependencies that still go the wrong way, each with what would
 * remove it. The list may only shrink: a new upward dependency fails, and so does an entry that is no
 * longer needed. Accounting and stock stay synchronous, inside the caller's transaction (see
 * CLAUDE.md), whatever the module order.
 *
 * <p>The same order holds for SQL: a module may query the tables of its own module and of the
 * modules below it (each table belongs to the module of its entity). Reading another module's
 * tables from below goes through a small interface that the owner implements, like
 * {@code inventory.DeliveryLookup}.
 */
class ModuleBoundariesTest {

    /** Modules from the bottom up, with their packages. */
    static final Map<String, List<String>> LAYERS = layers(
            "common", "common",             // errors, money, shared interfaces and enums
            "branch", "branch",             // the store's branches
            "vendor", "vendor",             // suppliers
            "audit", "audit",
            "identity", "auth access user", // people, roles, permissions, sessions
            "settings", "settings tax",     // store settings and tax rates
            "currency", "currency",
            "catalog", "catalog inventory", // what is sold and how much is in stock
            "customer", "customer",
            "sales", "sales payment hold",  // checkout, orders, returns, payments, held sales
            "marketing", "marketing",       // promotions, coupons, segments (checkout: sales.SalePromotions)
            "loyalty", "loyalty",           // points and tiers (checkout: sales.CustomerRewards)
            "accounting", "accounting",     // the books (sales.SaleBookkeeping, inventory.StockBookkeeping)
            "purchasing", "purchasing",     // purchase orders, deliveries, vendor invoices and payments
            "report", "report",
            "config", "config",             // security and wiring across modules
            "seed", "seed",                 // development data
            "app", "");                     // PosApplication

    /** Upward dependencies still to remove: "from -> to" and how. */
    static final Map<String, String> KNOWN_UPWARD = Map.ofEntries(
    );

    static JavaClasses classes;
    static Map<String, String> moduleOfPackage = new LinkedHashMap<>();
    static List<String> order = new ArrayList<>();

    @BeforeAll
    static void load() {
        LAYERS.forEach((module, packages) -> {
            order.add(module);
            packages.forEach(p -> moduleOfPackage.put(p, module));
        });
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.pos");
    }

    @Test
    void everyPackageBelongsToAModule() {
        Set<String> unknown = new TreeSet<>();
        for (JavaClass c : classes) {
            if (module(c) == null) {
                unknown.add(c.getPackageName());
            }
        }
        assertThat(unknown).as("packages missing from ModuleBoundariesTest.LAYERS").isEmpty();
    }

    @Test
    void modulesUseOnlyTheModulesBelowThem() {
        Map<String, Set<String>> upward = new TreeMap<>();
        for (JavaClass c : classes) {
            String from = module(c);
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                String to = module(d.getTargetClass());
                if (from != null && to != null && order.indexOf(to) > order.indexOf(from)) {
                    Set<String> examples = upward.computeIfAbsent(from + " -> " + to, k -> new TreeSet<>());
                    if (examples.size() < 5) {
                        examples.add(d.getOriginClass().getSimpleName() + " uses " + d.getTargetClass().getSimpleName());
                    }
                }
            }
        }
        Map<String, Set<String>> unexpected = new TreeMap<>(upward);
        unexpected.keySet().removeAll(KNOWN_UPWARD.keySet());
        Set<String> gone = new TreeSet<>(KNOWN_UPWARD.keySet());
        gone.removeAll(upward.keySet());

        assertThat(unexpected).as("modules using a module above them; depend downwards, publish an event, or move the class").isEmpty();
        assertThat(gone).as("no longer needed: remove from ModuleBoundariesTest.KNOWN_UPWARD").isEmpty();
    }

    /** Native SQL naming a table, e.g. {@code FROM orders} or {@code JOIN order_items}. */
    private static final Pattern SQL_TABLE = Pattern.compile("\\b(?:FROM|JOIN|UPDATE|INTO)\\s+([a-z][a-z0-9_]*)\\b");

    @Test
    void sqlUsesOnlyTablesOfTheModulesBelow() throws IOException {
        Map<String, String> ownerOfTable = new TreeMap<>();
        for (JavaClass c : classes) {
            if (c.isAnnotatedWith(Table.class)) {
                ownerOfTable.put(c.getAnnotationOfType(Table.class).name(), module(c));
            }
        }
        assertThat(ownerOfTable).as("entity tables found").containsKeys("orders", "offerings", "journal_entries");

        Map<String, Set<String>> upward = new TreeMap<>();
        Path root = Path.of("src/main/java/com/pos");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString();
                String from = rel.contains("/") ? moduleOfPackage.get(rel.substring(0, rel.indexOf('/'))) : "app";
                Matcher m = SQL_TABLE.matcher(read(f));
                while (m.find()) {
                    String to = ownerOfTable.get(m.group(1));
                    if (from != null && to != null && order.indexOf(to) > order.indexOf(from)) {
                        upward.computeIfAbsent(from + " -> " + to, k -> new TreeSet<>()).add(f.getFileName() + " reads " + m.group(1));
                    }
                }
            }
        }
        assertThat(upward).as("SQL reading the tables of a module above; ask the owner through an interface it implements").isEmpty();
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String module(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith("com.pos")) {
            return null;
        }
        if (pkg.equals("com.pos")) {
            return "app";
        }
        String top = pkg.substring("com.pos.".length()).split("\\.")[0];
        return moduleOfPackage.get(top);
    }

    private static Map<String, List<String>> layers(String... nameAndPackages) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < nameAndPackages.length; i += 2) {
            String p = nameAndPackages[i + 1];
            m.put(nameAndPackages[i], p.isBlank() ? List.of() : List.of(p.split(" ")));
        }
        return m;
    }
}
