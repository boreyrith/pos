package com.pos.marketing;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.marketing.AudienceService.CampaignDto;
import com.pos.marketing.AudienceService.CampaignRequest;
import com.pos.marketing.AudienceService.Member;
import com.pos.marketing.AudienceService.MessagePreview;
import com.pos.marketing.AudienceService.Preview;
import com.pos.marketing.AudienceService.RecipientDto;
import com.pos.marketing.AudienceService.SegmentDto;
import com.pos.marketing.AudienceService.SegmentRequest;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer segments and campaigns. Seeing them needs "View marketing"; creating them, preparing a
 * campaign and anything that exports customers' contact details needs "Manage campaigns".
 */
@RestController
public class AudienceController {

    private final AudienceService service;
    private final SettingsService settings;

    public AudienceController(AudienceService service, SettingsService settings) {
        this.service = service;
        this.settings = settings;
    }

    @GetMapping("/segments")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public List<SegmentDto> segments() {
        return service.listSegments();
    }

    /** How many customers rules match, and the first few (by spend). */
    @PostMapping("/segments/preview")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public Preview preview(@Valid @RequestBody SegmentRules rules) {
        return service.preview(rules);
    }

    @PostMapping("/segments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public SegmentDto createSegment(@Valid @RequestBody SegmentRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.createSegment(req, user);
    }

    @PutMapping("/segments/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public SegmentDto updateSegment(@PathVariable Long id, @Valid @RequestBody SegmentRequest req) {
        return service.updateSegment(id, req);
    }

    @DeleteMapping("/segments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public void deleteSegment(@PathVariable Long id) {
        service.deleteSegment(id);
    }

    @GetMapping(value = "/segments/{id}/members.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public ResponseEntity<String> membersCsv(@PathVariable Long id) {
        List<Member> members = service.allMembers(id);
        String name = service.segmentName(id);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone());
        StringBuilder csv = new StringBuilder("Code,Name,Phone,Email,Points,Orders,Spent,Last visit\n");
        for (Member m : members) {
            csv.append(esc(m.code())).append(',').append(esc(m.name())).append(',').append(esc(m.phone())).append(',')
                    .append(esc(m.email())).append(',').append(m.points()).append(',').append(m.orders()).append(',')
                    .append(m.spent().toPlainString()).append(',').append(m.lastVisit() == null ? "" : fmt.format(m.lastVisit())).append('\n');
        }
        service.exported(null, members.size(), "segment " + name);
        return csv(csv, "segment-" + slug(name) + ".csv");
    }

    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public List<CampaignDto> campaigns() {
        return service.listCampaigns();
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String message, Long segmentId, @Size(max = 12) String codePrefix) {
    }

    @PostMapping("/campaigns/preview-message")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public MessagePreview previewMessage(@Valid @RequestBody MessageRequest req) {
        return service.previewMessage(req.message(), req.segmentId(), req.codePrefix());
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public CampaignDto createCampaign(@Valid @RequestBody CampaignRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.createCampaign(req, user);
    }

    @PutMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public CampaignDto updateCampaign(@PathVariable Long id, @Valid @RequestBody CampaignRequest req) {
        return service.updateCampaign(id, req);
    }

    @DeleteMapping("/campaigns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public void deleteCampaign(@PathVariable Long id) {
        service.deleteCampaign(id);
    }

    /** Takes the segment's members, gives each their message (and code): done once, then exported. */
    @PostMapping("/campaigns/{id}/prepare")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public CampaignDto prepare(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.prepare(id, user);
    }

    @GetMapping("/campaigns/{id}/recipients")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public List<RecipientDto> recipients(@PathVariable Long id) {
        return service.recipients(id);
    }

    /** Each recipient's contact details, code and message, for sending with an SMS, Telegram or email tool. */
    @GetMapping(value = "/campaigns/{id}/recipients.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    public ResponseEntity<String> recipientsCsv(@PathVariable Long id) {
        List<RecipientDto> list = service.recipients(id);
        String name = service.campaignName(id);
        StringBuilder csv = new StringBuilder("Name,Phone,Email,Code,Message\n");
        for (RecipientDto r : list) {
            csv.append(esc(r.name())).append(',').append(esc(r.phone())).append(',').append(esc(r.email())).append(',')
                    .append(esc(r.code())).append(',').append(esc(r.message())).append('\n');
        }
        service.exported(id, list.size(), "campaign " + name);
        return csv(csv, "campaign-" + slug(name) + ".csv");
    }

    private ZoneId zone() {
        return settings.current().timezone();
    }

    private static ResponseEntity<String> csv(StringBuilder csv, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                // A byte-order mark so Excel reads Khmer text as UTF-8.
                .body("﻿" + csv);
    }

    private static String slug(String s) {
        String t = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return t.isEmpty() ? "export" : t;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        // Neutralise spreadsheet formulas in text cells.
        String t = s.matches("^[=+\\-@].*") && !s.matches("^-?\\d.*") ? "'" + s : s;
        return t.contains(",") || t.contains("\"") || t.contains("\n") ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }
}
