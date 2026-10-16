package com.raphael.beachcleanup.services;

import java.text.Normalizer;
import java.util.Locale;

public final class CatalogNames {
    private CatalogNames() {}
    public static String display(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Z}\\s]+", " ").strip();
    }
    public static String key(String value) {
        return Normalizer.normalize(display(value), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
