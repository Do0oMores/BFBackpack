package top.mores.bFBackpack.api;

import java.util.List;
import java.util.Map;

/** Saved plan plus revalidated effective equipment and explicit fallback reasons. */
public record LoadoutSnapshot(ClassLoadout saved, Map<LoadoutSlot, String> effectiveEquipment,
                              List<ValidationIssue> issues, long catalogRevision) {
    public LoadoutSnapshot { effectiveEquipment = Map.copyOf(effectiveEquipment); issues = List.copyOf(issues); }
}
