package top.mores.bFBackpack.api;

/**
 * Legacy synchronous verification of the exact attachment and skin plan, wrapped by
 * {@link SyncValidationProvider}. Runs on a bounded validation thread, never on the database worker.
 */
@FunctionalInterface
public interface AttachmentCompatibilityValidator {
    /** Must return UNVERIFIED when the real resources cannot be verified. */
    CompatibilityInfo validate(WeaponDefinition weapon, WeaponCustomization customization);
}
