package com.pos.catalog;

import java.time.Instant;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A catalog entry's image, kept apart so catalog lists never load image bytes. */
@Entity
@Table(name = "offering_images")
@Getter
@Setter
@NoArgsConstructor
public class OfferingImage {

    @Id
    @Column(name = "offering_id")
    private Long offeringId;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public OfferingImage(Long offeringId) {
        this.offeringId = offeringId;
    }
}
