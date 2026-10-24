package com.pos.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.AuthUser;
import com.pos.catalog.CatalogDtos.OfferingDto;
import com.pos.common.ApiException;
import com.pos.inventory.BranchContext;

/**
 * Quick picks for the sell screen: each person's favourites, and the branch's top sellers. Both
 * show only what can be sold now, with stock from the selling storage, like the normal tiles.
 */
@Service
public class QuickPicksService {

    /** A top seller and the units kept (sold, less returned) in the period. */
    public record TopSeller(OfferingDto offering, long sold) {
    }

    private final JdbcClient jdbc;
    private final OfferingService offerings;
    private final OfferingRepository repo;
    private final BranchContext branches;

    public QuickPicksService(JdbcClient jdbc, OfferingService offerings, OfferingRepository repo, BranchContext branches) {
        this.jdbc = jdbc;
        this.offerings = offerings;
        this.repo = repo;
        this.branches = branches;
    }

    /** Favourites in the order they were added. */
    @Transactional(readOnly = true)
    public List<OfferingDto> favourites(AuthUser user, List<Long> storageIds) {
        List<Long> ids = jdbc.sql("SELECT offering_id FROM user_favourites WHERE user_id = ? ORDER BY created_at, offering_id")
                .param(user.id()).query(Long.class).list();
        return inOrder(ids, storageIds);
    }

    @Transactional(readOnly = true)
    public List<Long> favouriteIds(AuthUser user) {
        return jdbc.sql("SELECT offering_id FROM user_favourites WHERE user_id = ?").param(user.id()).query(Long.class).list();
    }

    @Transactional
    public void addFavourite(Long offeringId, AuthUser user) {
        if (!repo.existsById(offeringId)) {
            throw ApiException.notFound("Catalog entry");
        }
        jdbc.sql("INSERT IGNORE INTO user_favourites (user_id, offering_id) VALUES (?, ?)").params(user.id(), offeringId).update();
    }

    @Transactional
    public void removeFavourite(Long offeringId, AuthUser user) {
        jdbc.sql("DELETE FROM user_favourites WHERE user_id = ? AND offering_id = ?").params(user.id(), offeringId).update();
    }

    /** What sold most in this branch over the last {@code days}, by units kept. */
    @Transactional(readOnly = true)
    public List<TopSeller> topSellers(AuthUser user, int days, int limit, List<Long> storageIds) {
        Map<Long, Long> sold = new HashMap<>();
        List<Long> ranked = jdbc.sql("""
                        SELECT i.offering_id, SUM(i.quantity - i.refunded_qty) AS units
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        WHERE o.branch_id = :branch AND o.status IN ('COMPLETED', 'PARTIALLY_REFUNDED')
                          AND o.created_at >= :since
                        GROUP BY i.offering_id
                        HAVING units > 0
                        ORDER BY units DESC, i.offering_id
                        LIMIT :max
                        """)
                .param("branch", branches.currentBranchId(user))
                .param("since", Instant.now().minus(days, ChronoUnit.DAYS))
                // Some may no longer be for sale; fetch extra so the list still fills up.
                .param("max", limit * 2)
                .query((rs, n) -> {
                    sold.put(rs.getLong(1), rs.getLong(2));
                    return rs.getLong(1);
                })
                .list();
        return inOrder(ranked, storageIds).stream().limit(limit)
                .map(o -> new TopSeller(o, sold.getOrDefault(o.id(), 0L))).toList();
    }

    /** The entries that can be sold now, in the given order. */
    private List<OfferingDto> inOrder(List<Long> ids, List<Long> storageIds) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, OfferingDto> byId = offerings.search(null, null, null, true, true, false, storageIds, ids,
                        PageRequest.of(0, 200, Sort.by("name")))
                .getContent().stream().collect(Collectors.toMap(OfferingDto::id, Function.identity()));
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            position.putIfAbsent(ids.get(i), i);
        }
        return byId.values().stream().sorted(Comparator.comparingInt(o -> position.get(o.id()))).toList();
    }
}
