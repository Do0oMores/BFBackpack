package top.mores.bFBackpack.catalog;

import java.util.Locale;

/** Canonical resource IDs, independent of Bukkit NamespacedKey. */
public final class Ids {
    private Ids() {}
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing resource ID");
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
            throw new IllegalArgumentException("Invalid namespaced ID: " + raw);
        return id;
    }
    public static String attachmentType(String raw) {
        if (raw == null || !raw.trim().matches("[a-zA-Z][a-zA-Z0-9_]{0,31}"))
            throw new IllegalArgumentException("Invalid attachment type: " + raw);
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
