package com.unl0cker.hibernate;

import java.util.Locale;

final class Format {

    private Format() {
    }

    /** 1.86 GB, 412 MB. */
    static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) {
            return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.0f MB", mb);
    }
}
