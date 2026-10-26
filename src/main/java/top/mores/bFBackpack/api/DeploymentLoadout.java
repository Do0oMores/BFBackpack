package top.mores.bFBackpack.api;

import java.util.Map;

/** Versioned deployment description. Adapter must require deployable AND revalidate at item creation. */
public record DeploymentLoadout(int protocolVersion, LoadoutSnapshot loadout,
        Map<LoadoutSlot, DeploymentEquipment> equipment, boolean deployable) {
    public DeploymentLoadout { equipment = Map.copyOf(equipment); }
}
