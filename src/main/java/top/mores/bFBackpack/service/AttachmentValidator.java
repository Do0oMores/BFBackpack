package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import java.util.*;

/** Catalog compatibility is a necessary condition; it is never proof of real TaCZ compatibility. */
public final class AttachmentValidator {
    private final UnlockService unlocks;
    public AttachmentValidator(UnlockService unlocks) { this.unlocks = unlocks; }
    public ResultCode validate(CatalogSnapshot catalog, Set<String> permanent, PermissionSnapshot permissions,
                               WeaponDefinition weapon, WeaponCustomization plan) {
        if (!plan.skinId().isEmpty()) return ResultCode.ATTACHMENT_INVALID;
        if (!weapon.attachmentsEnabled() && !plan.attachments().isEmpty()) return ResultCode.ATTACHMENT_INVALID;
        for (var entry : plan.attachments().entrySet()) {
            AttachmentDefinition attachment = catalog.attachments().get(entry.getValue());
            if (attachment == null || !attachment.enabled()) return ResultCode.NOT_FOUND;
            if (!catalog.attachmentTypes().contains(entry.getKey()) || !attachment.type().equals(entry.getKey())) return ResultCode.ATTACHMENT_INVALID;
            if (!attachment.allowedWeapons().contains(weapon.id()) && Collections.disjoint(weapon.tags(), attachment.allowedWeaponTags())) return ResultCode.ATTACHMENT_INVALID;
            if (!unlocks.resolve(catalog, permanent, permissions, attachment.id()).unlocked()) return ResultCode.LOCKED;
        }
        return ResultCode.OK;
    }
}
