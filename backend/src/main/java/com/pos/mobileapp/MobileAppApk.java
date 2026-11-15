package com.pos.mobileapp;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** The Android install file itself (one row), apart from its details so reading them never loads it. */
@Entity
@Table(name = "mobile_app_apk")
@Getter
@Setter
@NoArgsConstructor
public class MobileAppApk {

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Short id = MobileApp.ID;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    MobileAppApk(byte[] data) {
        this.data = data;
    }
}
