package top.mores.bFBackpack.api;

/** Player-visible catalog entry. Class filtering and unlock status are separate checks. */
public record WeaponCatalogEntry(WeaponDefinition weapon, UnlockResult unlock, ResultCode access) {}
