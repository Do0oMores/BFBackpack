package top.mores.bFBackpack.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned deployment description. The adapter must require {@link #deployable()} AND revalidate the
 * current state at item creation.
 *
 * @param protocolVersion JSON protocol version of this shape
 * @param binding         exact player/class/revision/provider state this description came from
 * @param loadout         saved plan plus revalidated effective equipment and catalog issues
 * @param equipment       per-slot data for a future adapter invocation; never a generated item
 * @param runtimeIssues   per-slot runtime verification problems, empty when everything is verified
 * @param status          deployment classification; {@code OK} is the only deployable status
 * @param deployable      true exactly when {@code status} is {@code OK}; kept as a field so the JSON
 *                        bridge exposes it without a lookup
 */
public record DeploymentLoadout(int protocolVersion, DeploymentBinding binding, LoadoutSnapshot loadout,
        Map<LoadoutSlot, DeploymentEquipment> equipment, List<ValidationIssue> runtimeIssues,
        ResultCode status, boolean deployable) {

    public DeploymentLoadout {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(loadout, "loadout");
        Objects.requireNonNull(status, "status");
        equipment = Map.copyOf(equipment);
        runtimeIssues = List.copyOf(runtimeIssues);
        if (deployable != (status == ResultCode.OK))
            throw new IllegalArgumentException("deployable must equal (status == OK)");
    }

    /** Build a description whose deployable flag is derived from the status. */
    public DeploymentLoadout(int protocolVersion, DeploymentBinding binding, LoadoutSnapshot loadout,
            Map<LoadoutSlot, DeploymentEquipment> equipment, List<ValidationIssue> runtimeIssues, ResultCode status) {
        this(protocolVersion, binding, loadout, equipment, runtimeIssues, status, status == ResultCode.OK);
    }
}
