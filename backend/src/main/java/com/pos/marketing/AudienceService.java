package com.pos.marketing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.customer.CustomerRepository;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * Segments (saved groups of customers on file, by rules) and campaigns (a message to a segment,
 * optionally with a coupon code for each customer). Members are worked out from customers and their
 * sales whenever they are needed; a prepared campaign keeps who it went to, their message and code.
 */
@Service
public class AudienceService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Sales that count as bought: paid, including partly or fully refunded ones (spend is net of refunds). */
    private static final String PAID = "('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')";
    /** The most customers one campaign goes to. */
    private static final int MAX_RECIPIENTS = 5000;

    private final SegmentRepository segments;
    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final PromotionRepository promotions;
    private final PromotionService promotionService;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final SettingsService settings;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public AudienceService(SegmentRepository segments, CampaignRepository campaigns, CampaignRecipientRepository recipients,
                           PromotionRepository promotions, PromotionService promotionService, CustomerRepository customers,
                           UserRepository users, SettingsService settings, AuditService audit, JdbcClient jdbc) {
        this.segments = segments;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.promotions = promotions;
        this.promotionService = promotionService;
        this.customers = customers;
        this.users = users;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- DTOs

    public record SegmentRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
                                 @NotNull @Valid SegmentRules rules) {
    }

    public record SegmentDto(Long id, String name, String description, SegmentRules rules, long members, String createdBy,
                             Instant createdAt, Instant updatedAt) {
    }

    /** A customer in a segment, with what they have bought. */
    public record Member(Long id, String code, String name, String phone, String email, int points, long orders, BigDecimal spent,
                         Instant lastVisit, Instant createdAt) {
    }

    public record Preview(long count, List<Member> sample) {
    }

    public record CampaignRequest(@NotBlank @Size(max = 120) String name, @NotNull Long segmentId,
                                  @NotBlank @Size(max = 1000) String message, Long promotionId,
                                  @Size(max = 12) @Pattern(regexp = "[A-Za-z0-9]*", message = "Use letters and digits only") String codePrefix,
                                  LocalDate codesExpireOn) {
    }

    /**
     * @param codesUsed codes from this campaign used at least once
     * @param sales     sales that used one of its codes (not cancelled)
     * @param revenue   what those sales came to, after refunds
     * @param discount  what its codes took off
     */
    public record CampaignDto(Long id, String name, Long segmentId, String segmentName, String channel, String message,
                              Long promotionId, String promotionName, String codePrefix, LocalDate codesExpireOn, Campaign.Status status,
                              int recipients, String preparedBy, Instant preparedAt, String createdBy, Instant createdAt, long codesUsed,
                              long sales, BigDecimal revenue, BigDecimal discount) {
    }

    public record RecipientDto(Long customerId, String name, String phone, String email, String code, boolean codeUsed, String message) {
    }

    public record MessagePreview(String message, String sampleName) {
    }

    // ---------------------------------------------------------------- segments

    @Transactional(readOnly = true)
    public List<SegmentDto> listSegments() {
        return segments.findAllByOrderByNameAsc().stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public Preview preview(SegmentRules rules) {
        return new Preview(count(rules), members(rules, 10));
    }

    @Transactional
    public SegmentDto createSegment(SegmentRequest req, AuthUser user) {
        Segment s = new Segment();
        s.setName(req.name().trim());
        s.setDescription(blankToNull(req.description()));
        s.setRules(JSON.writeValueAsString(req.rules()));
        s.setCreatedBy(users.getReferenceById(user.id()));
        segments.saveAndFlush(s);
        audit.log(AuditAction.SEGMENT_CREATED, "Segment", s.getId(), "Added customer segment " + s.getName());
        return dto(s);
    }

    @Transactional
    public SegmentDto updateSegment(Long id, SegmentRequest req) {
        Segment s = findSegment(id);
        Changes changes = new Changes().add("Name", s.getName(), req.name().trim()).add("Rules", s.getRules(), JSON.writeValueAsString(req.rules()));
        s.setName(req.name().trim());
        s.setDescription(blankToNull(req.description()));
        s.setRules(JSON.writeValueAsString(req.rules()));
        s.setUpdatedAt(Instant.now());
        audit.log(AuditAction.SEGMENT_UPDATED, "Segment", s.getId(), "Changed customer segment " + s.getName(), changes);
        return dto(s);
    }

    @Transactional
    public void deleteSegment(Long id) {
        Segment s = findSegment(id);
        segments.delete(s);
        audit.log(AuditAction.SEGMENT_DELETED, "Segment", id, "Deleted customer segment " + s.getName());
    }

    /** Everyone in a segment, for export. */
    @Transactional(readOnly = true)
    public List<Member> allMembers(Long segmentId) {
        return members(rules(findSegment(segmentId)), MAX_RECIPIENTS);
    }

    @Transactional(readOnly = true)
    public String segmentName(Long segmentId) {
        return findSegment(segmentId).getName();
    }

    private SegmentDto dto(Segment s) {
        SegmentRules r = rules(s);
        return new SegmentDto(s.getId(), s.getName(), s.getDescription(), r, count(r), s.getCreatedBy().getFullName(), s.getCreatedAt(),
                s.getUpdatedAt());
    }

    private static SegmentRules rules(Segment s) {
        return JSON.readValue(s.getRules(), SegmentRules.class);
    }

    private Segment findSegment(Long id) {
        return segments.findById(id).orElseThrow(() -> ApiException.notFound("Segment"));
    }

    /** The members query: active customers on file with their paid sales, filtered by the rules. */
    private record Query(String sql, Map<String, Object> params) {
    }

    private static Query query(SegmentRules r) {
        StringBuilder where = new StringBuilder("c.is_default = FALSE AND c.active = TRUE");
        Map<String, Object> p = new HashMap<>();
        Instant now = Instant.now();
        if (r.minSpent() != null) {
            where.append(" AND COALESCE(s.spent, 0) >= :minSpent");
            p.put("minSpent", r.minSpent());
        }
        if (r.maxSpent() != null) {
            where.append(" AND COALESCE(s.spent, 0) <= :maxSpent");
            p.put("maxSpent", r.maxSpent());
        }
        if (r.minOrders() != null) {
            where.append(" AND COALESCE(s.orders, 0) >= :minOrders");
            p.put("minOrders", r.minOrders());
        }
        if (r.maxOrders() != null) {
            where.append(" AND COALESCE(s.orders, 0) <= :maxOrders");
            p.put("maxOrders", r.maxOrders());
        }
        if (r.boughtWithinDays() != null) {
            where.append(" AND s.last_visit >= :boughtSince");
            p.put("boughtSince", Timestamp.from(now.minus(Duration.ofDays(r.boughtWithinDays()))));
        }
        if (r.notBoughtForDays() != null) {
            where.append(" AND s.last_visit < :lapsedBefore");
            p.put("lapsedBefore", Timestamp.from(now.minus(Duration.ofDays(r.notBoughtForDays()))));
        }
        if (r.joinedWithinDays() != null) {
            where.append(" AND c.created_at >= :joinedSince");
            p.put("joinedSince", Timestamp.from(now.minus(Duration.ofDays(r.joinedWithinDays()))));
        }
        if (r.minPoints() != null) {
            where.append(" AND c.points_balance >= :minPoints");
            p.put("minPoints", r.minPoints());
        }
        if (r.hasPhone() != null) {
            where.append(r.hasPhone() ? " AND c.phone IS NOT NULL AND c.phone <> ''" : " AND (c.phone IS NULL OR c.phone = '')");
        }
        if (r.hasEmail() != null) {
            where.append(r.hasEmail() ? " AND c.email IS NOT NULL AND c.email <> ''" : " AND (c.email IS NULL OR c.email = '')");
        }
        if (r.categoryIds() != null && !r.categoryIds().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM orders o JOIN order_items i ON i.order_id = o.id JOIN offerings f ON f.id = i.offering_id"
                    + " WHERE o.customer_id = c.id AND o.status IN " + PAID + " AND f.category_id IN (:categories))");
            p.put("categories", r.categoryIds());
        }
        if (r.minTier() != null) {
            where.append(" AND c.tier IN (:tiers)");
            p.put("tiers", java.util.Arrays.stream(com.pos.customer.Tier.values()).filter(t -> t.atLeast(r.minTier())).map(Enum::name).toList());
        }
        String sql = """
                SELECT c.id, c.code, c.name, c.phone, c.email, c.points_balance, c.created_at,
                       COALESCE(s.orders, 0) AS orders, COALESCE(s.spent, 0) AS spent, s.last_visit
                FROM customers c
                LEFT JOIN (SELECT customer_id, COUNT(*) AS orders, SUM(total - refunded_total) AS spent, MAX(completed_at) AS last_visit
                           FROM orders WHERE status IN %s GROUP BY customer_id) s ON s.customer_id = c.id
                WHERE %s
                """.formatted(PAID, where);
        return new Query(sql, p);
    }

    private long count(SegmentRules rules) {
        Query q = query(rules);
        return jdbc.sql("SELECT COUNT(*) FROM (" + q.sql() + ") m").params(q.params()).query(Long.class).single();
    }

    private List<Member> members(SegmentRules rules, int limit) {
        Query q = query(rules);
        return jdbc.sql(q.sql() + " ORDER BY spent DESC, c.name LIMIT " + limit).params(q.params())
                .query((rs, n) -> new Member(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("phone"),
                        rs.getString("email"), rs.getInt("points_balance"), rs.getLong("orders"), rs.getBigDecimal("spent"),
                        rs.getTimestamp("last_visit") == null ? null : rs.getTimestamp("last_visit").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- campaigns

    @Transactional(readOnly = true)
    public List<CampaignDto> listCampaigns() {
        List<Campaign> all = campaigns.findAllNewestFirst();
        Map<Long, long[]> used = new HashMap<>();
        jdbc.sql("""
                        SELECT r.campaign_id, COUNT(*) AS used FROM campaign_recipients r JOIN coupon_codes cc ON cc.id = r.coupon_code_id
                        WHERE cc.used_count > 0 GROUP BY r.campaign_id
                        """)
                .query((rs, n) -> used.put(rs.getLong("campaign_id"), new long[] {rs.getLong("used")})).list();
        Map<Long, Object[]> results = new HashMap<>();
        jdbc.sql("""
                        SELECT r.campaign_id, COUNT(DISTINCT o.id) AS sales, COALESCE(SUM(o.total - o.refunded_total), 0) AS revenue,
                               COALESCE(SUM(op.amount), 0) AS discount
                        FROM campaign_recipients r
                        JOIN order_promotions op ON op.coupon_code_id = r.coupon_code_id
                        JOIN orders o ON o.id = op.order_id
                        WHERE o.status <> 'CANCELLED'
                        GROUP BY r.campaign_id
                        """)
                .query((rs, n) -> results.put(rs.getLong("campaign_id"),
                        new Object[] {rs.getLong("sales"), rs.getBigDecimal("revenue"), rs.getBigDecimal("discount")})).list();
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return all.stream().map(c -> {
            Object[] r = results.getOrDefault(c.getId(), new Object[] {0L, zero, zero});
            return new CampaignDto(c.getId(), c.getName(), c.getSegment() == null ? null : c.getSegment().getId(),
                    c.getSegment() != null ? c.getSegment().getName() : c.getSegmentName(), c.getChannel(), c.getMessage(),
                    c.getPromotion() == null ? null : c.getPromotion().getId(), c.getPromotion() == null ? null : c.getPromotion().getName(),
                    c.getCodePrefix(), c.getCodesExpireOn(), c.getStatus(), c.getRecipients(),
                    c.getPreparedBy() == null ? null : c.getPreparedBy().getFullName(), c.getPreparedAt(), c.getCreatedBy().getFullName(),
                    c.getCreatedAt(), used.getOrDefault(c.getId(), new long[] {0})[0], (Long) r[0], (BigDecimal) r[1], (BigDecimal) r[2]);
        }).toList();
    }

    @Transactional
    public CampaignDto createCampaign(CampaignRequest req, AuthUser user) {
        Campaign c = new Campaign();
        apply(c, req);
        c.setCreatedBy(users.getReferenceById(user.id()));
        campaigns.saveAndFlush(c);
        audit.log(AuditAction.CAMPAIGN_CREATED, "Campaign", c.getId(), "Added campaign " + c.getName() + " for " + c.getSegment().getName());
        return find(c.getId());
    }

    @Transactional
    public CampaignDto updateCampaign(Long id, CampaignRequest req) {
        Campaign c = draft(id);
        apply(c, req);
        return find(id);
    }

    @Transactional
    public void deleteCampaign(Long id) {
        Campaign c = draft(id);
        campaigns.delete(c);
        audit.log(AuditAction.CAMPAIGN_DELETED, "Campaign", id, "Deleted draft campaign " + c.getName());
    }

    /**
     * Takes the segment's members now, gives each a code of their own (when the campaign has a
     * promotion), writes each their message, and keeps them as the recipients. Done once.
     */
    @Transactional
    public CampaignDto prepare(Long id, AuthUser user) {
        Campaign c = draft(id);
        if (c.getSegment() == null) {
            throw ApiException.badRequest("This campaign's segment was deleted: pick another one");
        }
        List<Member> members = members(rules(c.getSegment()), MAX_RECIPIENTS + 1);
        if (members.isEmpty()) {
            throw ApiException.badRequest("Nobody is in " + c.getSegment().getName() + " right now");
        }
        if (members.size() > MAX_RECIPIENTS) {
            throw ApiException.badRequest("A campaign can go to at most " + MAX_RECIPIENTS + " customers: narrow the segment");
        }
        String store = settings.current().name();
        List<CampaignRecipient> out = new ArrayList<>();
        for (Member m : members) {
            CouponCode code = c.getPromotion() == null ? null
                    : promotionService.issue(c.getPromotion(), customers.getReferenceById(m.id()), c.getCodePrefix(), c.getCodesExpireOn(), user);
            out.add(new CampaignRecipient(c, m.id(), m.name(), m.phone(), m.email(), code,
                    render(c.getMessage(), m.name(), code == null ? null : code.getCode(), m.points(), store)));
        }
        recipients.saveAll(out);
        c.setRecipients(out.size());
        c.setSegmentName(c.getSegment().getName());
        c.setStatus(Campaign.Status.PREPARED);
        c.setPreparedBy(users.getReferenceById(user.id()));
        c.setPreparedAt(Instant.now());
        audit.log(AuditAction.CAMPAIGN_PREPARED, "Campaign", c.getId(), "Prepared campaign " + c.getName() + " for " + out.size()
                + " customers" + (c.getPromotion() == null ? "" : ", with a code each for " + c.getPromotion().getName()));
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<RecipientDto> recipients(Long id) {
        return recipients.findByCampaign(id).stream().map(r -> new RecipientDto(r.getCustomerId(), r.getName(), r.getPhone(), r.getEmail(),
                r.getCouponCode() == null ? null : r.getCouponCode().getCode(),
                r.getCouponCode() != null && r.getCouponCode().getUsedCount() > 0, r.getMessage())).toList();
    }

    /** Recorded when contact details leave the system. */
    @Transactional
    public void exported(Long campaignId, int rows, String what) {
        audit.log(AuditAction.CAMPAIGN_EXPORTED, campaignId == null ? "Segment" : "Campaign", campaignId,
                "Exported " + rows + " customers' contact details: " + what);
    }

    @Transactional(readOnly = true)
    public String campaignName(Long id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign")).getName();
    }

    /** The message as the first member of the segment would get it (with a sample code in the campaign's format). */
    @Transactional(readOnly = true)
    public MessagePreview previewMessage(String message, Long segmentId, String codePrefix) {
        List<Member> first = segmentId == null ? List.of() : members(rules(findSegment(segmentId)), 1);
        String name = first.isEmpty() ? "Sokha" : first.getFirst().name();
        int points = first.isEmpty() ? 250 : first.getFirst().points();
        String sample = (codePrefix == null || codePrefix.isBlank() ? "" : codePrefix.trim().toUpperCase() + "-") + "7K3M9Q2P";
        return new MessagePreview(render(message, name, sample, points, settings.current().name()), name);
    }

    private CampaignDto find(Long id) {
        return listCampaigns().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    private Campaign draft(Long id) {
        Campaign c = campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
        if (c.getStatus() != Campaign.Status.DRAFT) {
            throw ApiException.conflict("This campaign has already been prepared and can't change");
        }
        return c;
    }

    private void apply(Campaign c, CampaignRequest req) {
        Segment s = findSegment(req.segmentId());
        boolean wantsCode = req.message().contains("{code}");
        Promotion p = null;
        if (req.promotionId() != null) {
            p = promotions.findById(req.promotionId()).orElseThrow(() -> ApiException.badRequest("That promotion doesn't exist"));
            if (!p.isRequiresCode()) {
                throw ApiException.badRequest(p.getName() + " runs without a code: pick a promotion that needs a coupon code");
            }
            if (!wantsCode) {
                throw ApiException.badRequest("Put {code} in the message so each customer sees their code");
            }
        } else if (wantsCode) {
            throw ApiException.badRequest("Pick a promotion for {code}, or take {code} out of the message");
        }
        c.setName(req.name().trim());
        c.setSegment(s);
        c.setMessage(req.message().trim());
        c.setPromotion(p);
        c.setCodePrefix(p == null || req.codePrefix() == null || req.codePrefix().isBlank() ? null : req.codePrefix().trim().toUpperCase());
        c.setCodesExpireOn(p == null ? null : req.codesExpireOn());
    }

    /** Fills in {name} (first name), {fullname}, {code}, {points} and {store}. */
    static String render(String message, String fullName, String code, int points, String store) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+")[0];
        return message.replace("{name}", first).replace("{fullname}", fullName == null ? "" : fullName)
                .replace("{code}", code == null ? "" : code).replace("{points}", String.valueOf(points))
                .replace("{store}", store == null ? "" : store);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
