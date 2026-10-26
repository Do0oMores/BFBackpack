package top.mores.bFBackpack.api;

import java.util.Map;
import java.util.UUID;

/** Confirmed per-player, per-weapon customization. Revision zero denotes an unsaved empty plan. */
public record WeaponCustomization(UUID playerId, String weaponId, Map<String, String> attachments,
                                  String skinId, long revision) {
    public WeaponCustomization { attachments = Map.copyOf(attachments); }
}
