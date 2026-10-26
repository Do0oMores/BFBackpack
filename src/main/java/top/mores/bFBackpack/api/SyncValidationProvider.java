package top.mores.bFBackpack.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter from the synchronous validator interfaces to {@link ValidationProvider}.
 * <p>The synchronous call executes on the BFBackpack validation thread (never the database worker), so
 * a slow implementation only occupies one bounded validation slot. Prefer implementing
 * {@link ValidationProvider} directly when the real check needs to hop to another thread.</p>
 */
public final class SyncValidationProvider implements ValidationProvider {
    private final WeaponProviderValidator weapons;
    private final AttachmentCompatibilityValidator attachments;

    public SyncValidationProvider(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments) {
        this.weapons = Objects.requireNonNull(weapons, "weapons");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(weapons.validate(weapon), "weapon compatibility"));
    }

    @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(attachments.validate(weapon, customization), "attachment compatibility"));
    }
}
