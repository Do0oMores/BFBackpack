package top.mores.bFBackpack.catalog;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;

/** Atomic catalog/rule snapshot, captured once for each backend operation. */
public record CatalogSnapshot(Map<String, WeaponDefinition> weapons,
        Map<String, AttachmentDefinition> attachments, Set<String> tags,
        Set<String> attachmentTypes, ClassEquipmentRules rules, long revision) {
    public CatalogSnapshot {
        weapons = Map.copyOf(weapons); attachments = Map.copyOf(attachments);
        tags = Set.copyOf(tags); attachmentTypes = Set.copyOf(attachmentTypes);
    }
    public List<WeaponDefinition> filter(BackpackType type, LoadoutSlot slot, Set<String> requiredTags) {
        Set<String> normalized = new HashSet<>();
        requiredTags.forEach(tag -> normalized.add(Ids.normalize(tag)));
        if (!tags.containsAll(normalized)) throw new IllegalArgumentException("Unknown filter TAG");
        return weapons.values().stream().filter(WeaponDefinition::enabled)
                .filter(w -> w.tags().containsAll(normalized))
                .filter(w -> slot == null || w.slots().contains(slot))
                .filter(w -> type == null || Arrays.stream(LoadoutSlot.values())
                        .anyMatch(s -> (slot == null || s == slot) && rules.allows(type, s, w)))
                .sorted(Comparator.comparing(WeaponDefinition::id)).toList();
    }
    public Set<String> permissions() {
        Set<String> nodes = new HashSet<>();
        weapons.values().forEach(w -> { nodes.add(w.unlockPermission()); nodes.add(w.requiredPermission()); });
        attachments.values().forEach(a -> nodes.add(a.unlockPermission()));
        nodes.remove("");
        return Set.copyOf(nodes);
    }
}
