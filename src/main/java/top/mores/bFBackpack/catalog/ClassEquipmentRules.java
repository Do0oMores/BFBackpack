package top.mores.bFBackpack.catalog;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;

/** Immutable per-class, per-slot rules. Denies always take precedence. Missing rules deny access. */
public final class ClassEquipmentRules {
    public record SlotRule(Set<String> allowTags, Set<String> denyTags, Set<String> allowItems,
                           Set<String> denyItems, String defaultItem) {
        public SlotRule {
            allowTags = Set.copyOf(allowTags); denyTags = Set.copyOf(denyTags);
            allowItems = Set.copyOf(allowItems); denyItems = Set.copyOf(denyItems);
        }
        boolean allows(WeaponDefinition weapon) {
            if (denyItems.contains(weapon.id()) || !Collections.disjoint(denyTags, weapon.tags())) return false;
            return allowItems.contains(weapon.id()) || !Collections.disjoint(allowTags, weapon.tags());
        }
    }
    private final Map<BackpackType, Map<LoadoutSlot, SlotRule>> rules;
    public ClassEquipmentRules(Map<BackpackType, Map<LoadoutSlot, SlotRule>> rules) {
        Map<BackpackType, Map<LoadoutSlot, SlotRule>> copy = new EnumMap<>(BackpackType.class);
        rules.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        this.rules = Map.copyOf(copy);
    }
    public boolean allows(BackpackType type, LoadoutSlot slot, WeaponDefinition weapon) {
        SlotRule rule = rules.getOrDefault(type, Map.of()).get(slot);
        return weapon.enabled() && weapon.slots().contains(slot)
                && !weapon.deniedClasses().contains(type)
                && (weapon.allowedClasses().isEmpty() || weapon.allowedClasses().contains(type))
                && rule != null && rule.allows(weapon);
    }
    public String defaultItem(BackpackType type, LoadoutSlot slot) {
        SlotRule rule = rules.getOrDefault(type, Map.of()).get(slot);
        return rule == null ? null : rule.defaultItem();
    }
    public Map<LoadoutSlot, String> defaults(BackpackType type) {
        Map<LoadoutSlot, String> result = new EnumMap<>(LoadoutSlot.class);
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            String id = defaultItem(type, slot);
            if (id != null) result.put(slot, id);
        }
        return Map.copyOf(result);
    }
}
