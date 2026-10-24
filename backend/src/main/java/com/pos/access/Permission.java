package com.pos.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything a role can be allowed to do. The list is fixed in code (each endpoint checks one with
 * {@code @PreAuthorize("hasAuthority('...')")}); roles, in the database, pick from it. Some
 * permissions need another to be useful (refunding needs seeing orders): {@link #implied()} lists
 * them and they are added automatically when a role is saved.
 */
public enum Permission {

    SALES_SELL(Module.SALES, "Sell", "Use the sell screen, take cash and KHQR payments, print receipts"),
    ORDERS_VIEW(Module.SALES, "View orders", "See orders, receipts and payments"),
    ORDERS_CANCEL(Module.SALES, "Cancel unpaid orders", "Cancel orders still waiting for payment", "ORDERS_VIEW"),
    ORDERS_REFUND(Module.SALES, "Refunds and returns", "Refund orders in full or take back items", "ORDERS_VIEW"),
    HOLDS_MANAGE(Module.SALES, "Manage held sales", "Void anyone's held sale, take over one another till is using, restore expired ones", "SALES_SELL"),
    DISCOUNT_ITEMS(Module.SALES, "Discount items", "Give a % or amount off single items at checkout, up to the role's maximum", "SALES_SELL"),
    PRICE_OVERRIDE(Module.SALES, "Override prices", "Change an item's price at checkout, with a reason (not below cost)", "SALES_SELL"),
    PRICE_BELOW_COST(Module.SALES, "Sell below cost", "Override a price below the item's cost", "PRICE_OVERRIDE"),

    CUSTOMERS_VIEW(Module.CUSTOMERS, "View customers", "See customers, their details and purchase history"),
    CUSTOMERS_MANAGE(Module.CUSTOMERS, "Manage customers", "Add and edit customers (also from the sell screen)", "CUSTOMERS_VIEW"),

    CATALOG_MANAGE(Module.CATALOG, "Manage catalog", "Add and edit products, services, items, packages, prices, images, categories and units"),

    INVENTORY_VIEW(Module.INVENTORY, "View inventory", "See stock levels, stock history, adjustments and transfers"),
    STOCK_ADJUST(Module.INVENTORY, "Adjust stock", "Receive, remove and correct stock with a reason", "INVENTORY_VIEW"),
    STOCK_COUNT(Module.INVENTORY, "Count stock", "Run stock counts and apply the differences", "INVENTORY_VIEW"),
    STOCK_TRANSFER(Module.INVENTORY, "Transfer stock", "Move stock between storages and branches", "INVENTORY_VIEW"),

    PURCHASE_VIEW(Module.PURCHASING, "View purchasing", "See vendors, purchase orders, deliveries, invoices and payments"),
    VENDORS_MANAGE(Module.PURCHASING, "Manage vendors", "Add and edit vendors", "PURCHASE_VIEW"),
    PURCHASE_ORDER(Module.PURCHASING, "Purchase orders", "Create, edit, place and cancel purchase orders", "PURCHASE_VIEW"),
    PURCHASE_RECEIVE(Module.PURCHASING, "Receive deliveries", "Receive purchased goods into stock", "PURCHASE_VIEW"),
    PURCHASE_INVOICE(Module.PURCHASING, "Vendor invoices", "Record and void vendor invoices", "PURCHASE_VIEW"),
    PURCHASE_PAY(Module.PURCHASING, "Vendor payments", "Record payments to vendors", "PURCHASE_VIEW"),

    REPORTS_DASHBOARD(Module.REPORTS, "Sales dashboard", "Sales totals, top sellers, payment methods and cash by currency"),
    REPORTS_TAX(Module.REPORTS, "Tax report", "VAT collected and refunded, with CSV export"),
    REPORTS_SALES(Module.REPORTS, "Sales reports", "Sales, profit and margin by period, item, category, cashier, customer, payment; discounts and returns"),
    REPORTS_INVENTORY(Module.REPORTS, "Inventory reports", "Stock valuation at cost and stock movement summaries"),
    REPORTS_PURCHASING(Module.REPORTS, "Purchasing reports", "Purchases by vendor and what is owed, by age"),

    SETTINGS_VIEW(Module.SETTINGS, "View settings", "See company, tax, KHQR and location settings (never secrets)"),
    SETTINGS_COMPANY(Module.SETTINGS, "Company settings", "Company details, logo, receipt footer and time zone", "SETTINGS_VIEW"),
    SETTINGS_TAX(Module.SETTINGS, "Tax settings", "Tax mode, VAT rate and tax rates", "SETTINGS_VIEW"),
    SETTINGS_KHQR(Module.SETTINGS, "KHQR and Bakong", "Payment account, live/test mode and the Bakong API token", "SETTINGS_VIEW"),
    SETTINGS_CURRENCY(Module.SETTINGS, "Currencies", "Add currencies and turn them on or off", "CURRENCY_RATES"),
    CURRENCY_RATES(Module.SETTINGS, "Exchange rates", "Set exchange rates"),
    BRANCHES_MANAGE(Module.SETTINGS, "Branches", "Add and edit branches", "SETTINGS_VIEW"),
    STORAGES_MANAGE(Module.SETTINGS, "Storages", "Add and edit storages (in their own branch unless they work across branches)", "SETTINGS_VIEW"),

    USERS_VIEW(Module.USERS, "View users", "See staff accounts and roles"),
    USERS_MANAGE(Module.USERS, "Manage users", "Create and edit accounts, reset passwords, unlock, sign out", "USERS_VIEW"),
    ROLES_MANAGE(Module.USERS, "Manage roles", "Create and edit roles and their permissions", "USERS_VIEW"),
    AUDIT_VIEW(Module.USERS, "Audit log", "Read who did what and when"),

    ALL_BRANCHES(Module.SCOPE, "All branches", "Switch branch and see every branch in lists and reports; otherwise only their own branch");

    /** Groups for the role editor. */
    public enum Module {
        SALES("Sales"), CUSTOMERS("Customers"), CATALOG("Catalog"), INVENTORY("Inventory"), PURCHASING("Purchasing"), REPORTS("Reports"), SETTINGS("Settings"),
        USERS("Users and security"), SCOPE("Branch access");

        private final String label;

        Module(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Module module;
    private final String label;
    private final String description;
    private final String[] implied;

    Permission(Module module, String label, String description, String... implied) {
        this.module = module;
        this.label = label;
        this.description = description;
        this.implied = implied;
    }

    public Module module() {
        return module;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Set<Permission> implied() {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String p : implied) {
            out.add(valueOf(p));
        }
        return out;
    }

    /** {@code granted} plus everything they imply, transitively. */
    public static Set<Permission> withImplied(Set<Permission> granted) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        out.addAll(granted);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Permission p : EnumSet.copyOf(out.isEmpty() ? EnumSet.noneOf(Permission.class) : out)) {
                grew |= out.addAll(p.implied());
            }
        }
        return out;
    }
}
