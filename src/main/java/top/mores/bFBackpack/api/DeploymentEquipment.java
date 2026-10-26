package top.mores.bFBackpack.api;

import java.util.Map;

/** Complete data for one future adapter invocation; never contains a generated item. */
public record DeploymentEquipment(WeaponDefinition definition, WeaponCustomization customization,
        Map<String, AttachmentDefinition> attachmentDefinitions, CompatibilityInfo compatibility) {
    public DeploymentEquipment { attachmentDefinitions = Map.copyOf(attachmentDefinitions); }
}
