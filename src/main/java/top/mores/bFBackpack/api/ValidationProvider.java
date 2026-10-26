package top.mores.bFBackpack.api;

import java.util.concurrent.CompletableFuture;

/**
 * Runtime verification adapter for real Forge/TaCZ resources (API v3).
 *
 * <h2>Thread contract</h2>
 * <ul>
 *   <li>Called on a dedicated BFBackpack validation thread, never on the database worker and never on
 *       the Bukkit main thread.</li>
 *   <li>Must return quickly. The engine re-checks every slot through {@code allOf} and enforces
 *       {@code runtime-validation.timeout-ms}, so a returned future may complete later, but the method
 *       itself must not block waiting for another thread or for the Bukkit main thread.</li>
 *   <li>If TaCZ/Forge requires its own thread, marshal inside the adapter and complete the returned
 *       future from there. BFBackpack never blocks the database worker on Forge.</li>
 *   <li>After a timeout the caller already received {@link ResultCode#VALIDATION_TIMEOUT}. A late
 *       completion is ignored; it can never mark another loadout revision as deployable.</li>
 *   <li>Unregistering a provider stops new requests. Already accepted requests finish (or time out)
 *       with the registration they were accepted with.</li>
 * </ul>
 *
 * Implementations are trusted server integrations. Return {@link CompatibilityInfo#unverified} when a
 * resource cannot be inspected instead of guessing.
 */
public interface ValidationProvider {

    /** ABI this adapter was compiled against; must equal {@link BFBackpackAPI#API_VERSION}. */
    default int apiVersion() { return BFBackpackAPI.API_VERSION; }

    /** Verify the real provider resource, including gunId, without constructing an item. */
    CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon);

    /** Verify the exact attachment/skin plan against real provider resources. */
    CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization);
}
