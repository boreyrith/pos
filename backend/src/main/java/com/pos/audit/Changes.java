package com.pos.audit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects before/after values for an audit entry, keeping only fields that actually changed.
 * Usage: {@code Changes c = new Changes(); c.add("Price", oldPrice, newPrice); ...}
 */
public final class Changes {

    public record Change(String field, String from, String to) {
    }

    private final List<Change> list = new ArrayList<>();

    public Changes add(String field, Object from, Object to) {
        String a = text(from);
        String b = text(to);
        if (!Objects.equals(a, b)) {
            list.add(new Change(field, a, b));
        }
        return this;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public List<Change> list() {
        return List.copyOf(list);
    }

    /** "Price: 2.50 → 2.75; Active: true → false" */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Change c : list) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(c.field()).append(": ").append(c.from() == null ? "–" : c.from()).append(" → ")
                    .append(c.to() == null ? "–" : c.to());
        }
        return sb.toString();
    }

    Map<String, Object> asDetails() {
        return Map.of("changes", list);
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }
}
