package com.pos.report;

import java.util.List;
import java.util.Map;

/**
 * A report as a table: typed columns, rows keyed by column, a totals row and notes on how the
 * figures are worked out. The web app renders any report from this; CSV export uses it too.
 *
 * @param type of each column: text, int, decimal, money, percent (0-100), date, datetime
 */
public record Report(String key, String title, String description, List<Column> columns, List<Map<String, Object>> rows,
                     Map<String, Object> totals, List<String> notes, String currency) {

    public record Column(String key, String label, String type) {

        static Column text(String key, String label) {
            return new Column(key, label, "text");
        }

        static Column integer(String key, String label) {
            return new Column(key, label, "int");
        }

        static Column money(String key, String label) {
            return new Column(key, label, "money");
        }

        static Column percent(String key, String label) {
            return new Column(key, label, "percent");
        }

        static Column decimal(String key, String label) {
            return new Column(key, label, "decimal");
        }

        static Column date(String key, String label) {
            return new Column(key, label, "date");
        }

        static Column datetime(String key, String label) {
            return new Column(key, label, "datetime");
        }
    }

    /** The reports there are, for the menu: key, title, group and the permission they need. */
    public record Info(String key, String title, String group, String description, String permission, boolean dated) {
    }
}
