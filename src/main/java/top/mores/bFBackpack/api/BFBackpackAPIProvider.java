package top.mores.bFBackpack.api;

import java.util.Objects;

/** Immutable compatibility gate. Obtain the service from Bukkit ServicesManager in a Bukkit bridge. */
public record BFBackpackAPIProvider(BFBackpackAPI api) {
    public BFBackpackAPIProvider { Objects.requireNonNull(api); }
    /** Explicitly reject an incompatible consumer rather than relying on casts across classloaders. */
    public LoadoutResult<BFBackpackAPI> requireVersion(int requiredVersion) {
        return api.apiVersion() == requiredVersion ? LoadoutResult.ok(api)
                : LoadoutResult.fail(ResultCode.API_INCOMPATIBLE, "Unsupported API version " + requiredVersion);
    }
}
