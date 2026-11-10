package com.pos.common;

import java.time.ZoneId;

/**
 * The store's time zone (Settings > Store), for modules below settings that turn dates into
 * instants. {@code settings.SettingsService} implements it.
 */
public interface StoreTimeZone {

    ZoneId timezone();
}
