package com.pos.catalog;

/** Barcode helpers. */
public final class Barcodes {

    /**
     * GS1 sets aside EAN-13 codes starting with 200–299 for use inside a store; codes the system
     * generates start with 200 so they never clash with a manufacturer's barcode.
     */
    public static final String IN_STORE_PREFIX = "200";

    private Barcodes() {
    }

    /** Adds the EAN-13 check digit to 12 digits. */
    public static String ean13(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (twelve.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return twelve + (10 - sum % 10) % 10;
    }
}
