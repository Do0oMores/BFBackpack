package top.mores.bFBackpack.catalog;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** YAML catalog loader. Structural errors reject a reload atomically; invalid entries are quarantined. */
public final class WeaponCatalog {
    private volatile CatalogSnapshot snapshot;
    private final Consumer<String> diagnostics;
    public WeaponCatalog(Consumer<String> diagnostics) { this.diagnostics = diagnostics; }
    public CatalogSnapshot snapshot() {
        CatalogSnapshot current = snapshot;
        if (current == null) throw new IllegalStateException("Catalog not loaded");
        return current;
    }
    public synchronized CatalogSnapshot reload(Path folder) throws IOException {
        Map<String, Object> wRoot = read(folder.resolve("weapons.yml"));
        Map<String, Object> aRoot = read(folder.resolve("attachments.yml"));
        Map<String, Object> rRoot = read(folder.resolve("class-rules.yml"));
        Set<String> tags = ids(list(wRoot, "tags"));
        if (tags.isEmpty()) throw new IOException("weapons.yml: tags registry is empty");
        Map<String, WeaponDefinition> weapons = new LinkedHashMap<>();
        Set<String> duplicateWeapons = new HashSet<>();
        entries(wRoot, "weapons").forEach((rawId, value) -> {
            try {
                String id = Ids.normalize(rawId);
                if (!duplicateWeapons.add(id)) { weapons.remove(id); throw new IllegalArgumentException("Duplicate normalized weapon ID " + id); }
                Map<String, Object> item = map(value);
                Map<String, Object> unlock = section(item, "unlock");
                Map<String, Object> restrictions = section(item, "restrictions");
                String provider = required(item, "provider").toUpperCase(Locale.ROOT);
                if (!provider.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid provider");
                WeaponDefinition weapon = new WeaponDefinition(id, provider,
                        Ids.normalize(required(item, "gun-id")), required(item, "display-name"),
                        required(item, "category"), knownIds(item, "tags", tags), enums(item, "slots", LoadoutSlot.class),
                        bool(unlock, "default-unlocked", false), text(unlock, "permission", ""),
                        bool(section(item, "attachments"), "enabled", false), bool(item, "enabled", true),
                        enums(restrictions, "allow-classes", BackpackType.class),
                        enums(restrictions, "deny-classes", BackpackType.class), text(restrictions, "permission", ""));
                if (weapon.tags().isEmpty() || weapon.slots().isEmpty()) throw new IllegalArgumentException("tags/slots must not be empty");
                weapons.put(id, weapon);
            } catch (RuntimeException e) { diagnostics.accept("weapons.yml [" + rawId + "]: " + e.getMessage()); }
        });
        Set<String> types = new HashSet<>();
        for (String raw : list(aRoot, "types")) types.add(Ids.attachmentType(raw));
        Map<String, AttachmentDefinition> attachments = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>(weapons.keySet());
        entries(aRoot, "attachments").forEach((rawId, value) -> {
            try {
                String id = Ids.normalize(rawId);
                if (!seen.add(id)) { attachments.remove(id); throw new IllegalArgumentException("Duplicate item ID " + id); }
                Map<String, Object> item = map(value);
                Map<String, Object> unlock = section(item, "unlock");
                String type = Ids.attachmentType(required(item, "type"));
                if (!types.contains(type)) throw new IllegalArgumentException("Unknown attachment type " + type);
                Set<String> allowedWeapons = ids(list(item, "allow-weapons"));
                if (!weapons.keySet().containsAll(allowedWeapons)) throw new IllegalArgumentException("Unknown allow-weapons ID");
                Set<String> allowedTags = knownIds(item, "allow-tags", tags);
                if (allowedTags.isEmpty() && allowedWeapons.isEmpty()) throw new IllegalArgumentException("Explicit compatibility allowlist required");
                attachments.put(id, new AttachmentDefinition(id, Ids.normalize(required(item, "provider-id")),
                        required(item, "display-name"), type, bool(item, "enabled", true),
                        bool(unlock, "default-unlocked", false), text(unlock, "permission", ""), allowedWeapons, allowedTags));
            } catch (RuntimeException e) { diagnostics.accept("attachments.yml [" + rawId + "]: " + e.getMessage()); }
        });
        Map<BackpackType, Map<LoadoutSlot, ClassEquipmentRules.SlotRule>> rules = new EnumMap<>(BackpackType.class);
        try {
            for (var classEntry : entries(rRoot, "classes").entrySet()) {
                BackpackType type = BackpackType.valueOf(classEntry.getKey());
                Map<LoadoutSlot, ClassEquipmentRules.SlotRule> slots = new EnumMap<>(LoadoutSlot.class);
                for (var slotEntry : map(classEntry.getValue()).entrySet()) {
                    LoadoutSlot slot = LoadoutSlot.valueOf(slotEntry.getKey());
                    Map<String, Object> rule = map(slotEntry.getValue());
                    String rawDefault = text(rule, "default", "");
                    String defaultId = rawDefault.isBlank() ? null : Ids.normalize(rawDefault);
                    Set<String> allow = knownIds(rule, "allow-items", weapons.keySet());
                    Set<String> deny = knownIds(rule, "deny-items", weapons.keySet());
                    var parsed = new ClassEquipmentRules.SlotRule(knownIds(rule, "allow-tags", tags),
                            knownIds(rule, "deny-tags", tags), allow, deny, defaultId);
                    if (defaultId != null) {
                        WeaponDefinition def = weapons.get(defaultId);
                        if (def == null || !def.enabled() || !def.defaultUnlocked() || !def.requiredPermission().isEmpty()
                                || !def.slots().contains(slot) || def.deniedClasses().contains(type)
                                || (!def.allowedClasses().isEmpty() && !def.allowedClasses().contains(type)) || !parsed.allows(def)) {
                            diagnostics.accept("class-rules.yml [" + type + "/" + slot + "]: invalid default " + defaultId + "; slot has no fallback");
                            parsed = new ClassEquipmentRules.SlotRule(parsed.allowTags(), parsed.denyTags(), allow, deny, null);
                        }
                    }
                    slots.put(slot, parsed);
                }
                rules.put(type, slots);
            }
            if (rules.size() != BackpackType.values().length) throw new IllegalArgumentException("All four classes must be defined");
        } catch (RuntimeException e) { throw new IOException("class-rules.yml: " + e.getMessage(), e); }
        if (weapons.values().stream().noneMatch(WeaponDefinition::enabled)) throw new IOException("No enabled weapons remain");
        CatalogSnapshot loaded = new CatalogSnapshot(weapons, attachments, tags, types,
                new ClassEquipmentRules(rules), snapshot == null ? 1 : snapshot.revision() + 1);
        snapshot = loaded;
        return loaded;
    }
    private static Map<String, Object> read(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setCodePointLimit(2_000_000);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return map(new Yaml(new SafeConstructor(options)).load(reader));
        } catch (RuntimeException e) { throw new IOException(path.getFileName() + ": " + e.getMessage(), e); }
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> input)) throw new IllegalArgumentException("Expected YAML mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((k, v) -> { if (!(k instanceof String s)) throw new IllegalArgumentException("Mapping keys must be strings"); result.put(s, v); });
        return result;
    }
    private static Map<String, Object> section(Map<String, Object> map, String key) { return map.containsKey(key) ? map(map.get(key)) : Map.of(); }
    private static Map<String, Object> entries(Map<String, Object> map, String key) { return map(map.get(key)); }
    private static String required(Map<String, Object> map, String key) {
        String value = text(map, key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof String s)) throw new IllegalArgumentException(key + " must be a string");
        return s.trim();
    }
    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException(key + " must be boolean");
        return b;
    }
    private static List<String> list(Map<String, Object> map, String key) {
        Object value = map.getOrDefault(key, List.of());
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(key + " must be a list");
        return list.stream().map(v -> { if (!(v instanceof String s)) throw new IllegalArgumentException(key + " contains a non-string"); return s; }).toList();
    }
    private static Set<String> ids(List<String> list) {
        Set<String> result = new HashSet<>(); list.forEach(raw -> result.add(Ids.normalize(raw))); return result;
    }
    private static Set<String> knownIds(Map<String, Object> map, String key, Set<String> known) {
        Set<String> result = ids(list(map, key));
        if (!known.containsAll(result)) throw new IllegalArgumentException(key + " contains unknown ID/TAG: " + result.stream().filter(s -> !known.contains(s)).toList());
        return result;
    }
    private static <E extends Enum<E>> Set<E> enums(Map<String, Object> map, String key, Class<E> type) {
        Set<E> result = EnumSet.noneOf(type);
        list(map, key).forEach(raw -> result.add(Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)))); return result;
    }
}
