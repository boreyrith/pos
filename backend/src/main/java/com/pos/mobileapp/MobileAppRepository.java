package com.pos.mobileapp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MobileAppRepository extends JpaRepository<MobileApp, Short> {

    /** Counted without loading the row, so downloads at once all count. */
    @Modifying
    @Query("update MobileApp m set m.apkDownloads = m.apkDownloads + 1 where m.id = 1")
    int countDownload();
}
