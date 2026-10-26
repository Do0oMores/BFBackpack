package top.mores.bFBackpack.api;

/** Pluggable verification of the exact attachment and skin plan against real provider resources. */
@FunctionalInterface
public interface AttachmentCompatibilityValidator {
    /** Called on the worker; must return UNVERIFIED when resources cannot be verified. */
    CompatibilityInfo validate(WeaponDefinition weapon, WeaponCustomization customization);
}
