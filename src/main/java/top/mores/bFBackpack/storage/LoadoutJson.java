package top.mores.bFBackpack.storage;

import com.google.gson.*;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.sql.SQLException;
import java.util.*;

/** Versioned JSON payloads. Corrupt or newer data fails explicitly and is never reset silently. */
public final class LoadoutJson {
    public static final int VERSION = 1;
    private static final Gson GSON = new Gson();
    private LoadoutJson() {}
    public static String encode(ClassLoadout loadout) {
        JsonObject root = root(); root.add("equipment", GSON.toJsonTree(loadout.equipment())); return GSON.toJson(root);
    }
    public static String encode(WeaponCustomization plan) {
        JsonObject root = root(); root.add("attachments", GSON.toJsonTree(plan.attachments()));
        root.addProperty("skinId", plan.skinId()); return GSON.toJson(root);
    }
    public static ClassLoadout loadout(UUID player, BackpackType type, String json, long revision) throws SQLException {
        try {
            Map<LoadoutSlot, String> equipment = new EnumMap<>(LoadoutSlot.class);
            read(json).getAsJsonObject("equipment").entrySet().forEach(e -> equipment.put(LoadoutSlot.valueOf(e.getKey()), string(e.getValue())));
            return new ClassLoadout(player, type, equipment, revision);
        } catch (RuntimeException e) { throw new SQLException("Invalid loadout JSON/schema", e); }
    }
    public static WeaponCustomization customization(UUID player, String weapon, String json, long revision) throws SQLException {
        try {
            JsonObject root = read(json); Map<String, String> attachments = new HashMap<>();
            root.getAsJsonObject("attachments").entrySet().forEach(e -> attachments.put(e.getKey(), string(e.getValue())));
            return new WeaponCustomization(player, weapon, attachments, string(root.get("skinId")), revision);
        } catch (RuntimeException e) { throw new SQLException("Invalid customization JSON/schema", e); }
    }
    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }
    private static JsonObject root() { JsonObject root = new JsonObject(); root.addProperty("schemaVersion", VERSION); return root; }
    private static JsonObject read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != VERSION) throw new IllegalArgumentException("Unsupported schemaVersion");
        return root;
    }
}
