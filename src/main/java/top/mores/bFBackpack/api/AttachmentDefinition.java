package top.mores.bFBackpack.api;

import java.util.Set;

/** An attachment and its explicit catalog compatibility allowlist; runtime verification is separate. */
public record AttachmentDefinition(String id, String providerId, String displayName, String type,
        boolean enabled, boolean defaultUnlocked, String unlockPermission,
        Set<String> allowedWeapons, Set<String> allowedWeaponTags) {
    public AttachmentDefinition {
        allowedWeapons = Set.copyOf(allowedWeapons); allowedWeaponTags = Set.copyOf(allowedWeaponTags);
    }
}
