package top.mores.bFBackpack.api;

import top.mores.bFBackpack.backpack.BackpackType;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact state a deployment description was derived from. Phase two must re-request the description when
 * any of these values no longer match the live state; a description is never mixed across revisions.
 *
 * @param playerId             player UUID the description was built for
 * @param soldierClass         class of the described loadout
 * @param loadoutRevision      confirmed revision of that class loadout
 * @param catalogRevision      catalog snapshot revision used for legality and resource IDs
 * @param sessionGeneration    join generation the permission snapshot belongs to
 * @param permissionCapturedAt epoch millis at which permissions were read on the Bukkit thread
 * @param providerId           provider that produced the runtime result, empty when none is installed
 * @param providerGeneration   registration generation of that provider, zero when none is installed
 * @param stillCurrent         false when a loadout/customization edit or a catalog reload landed while
 *                             the description was being verified; deployable is then false
 */
public record DeploymentBinding(UUID playerId, BackpackType soldierClass, long loadoutRevision,
        long catalogRevision, long sessionGeneration, long permissionCapturedAt, String providerId,
        long providerGeneration, boolean stillCurrent) {
    public DeploymentBinding {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(soldierClass, "soldierClass");
        Objects.requireNonNull(providerId, "providerId");
    }
}
