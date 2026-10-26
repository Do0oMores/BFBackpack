package top.mores.bFBackpack;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.net.URLClassLoader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PluginContractTest {
    @Test void pluginDescriptorLoadsWithBukkitAndDeclaresCommandsAndPermissions() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            PluginDescriptionFile descriptor = new PluginDescriptionFile(stream);
            assertEquals("top.mores.bFBackpack.BFBackpack", descriptor.getMain()); assertEquals("1.20", descriptor.getAPIVersion());
            assertEquals("2.0.0-SNAPSHOT", descriptor.getVersion()); assertTrue(descriptor.getCommands().containsKey("bfbackpack"));
            assertTrue(descriptor.getPermissions().stream().anyMatch(p -> p.getName().equals("bfbackpack.admin.unlock")));
        }
    }
    @Test void configLoadsAndPublicApiHasNoBukkitClassloaderDependency() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            Map<?, ?> config = new Yaml(new SafeConstructor(new LoaderOptions())).load(stream); assertNotNull(config.get("database"));
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{top.mores.bFBackpack.api.BFBackpackAPI.class.getProtectionDomain().getCodeSource().getLocation()}, null)) {
            Class<?> api = loader.loadClass("top.mores.bFBackpack.api.BFBackpackAPI"); assertTrue(api.getDeclaredMethods().length > 15);
            Class<?> type = loader.loadClass("top.mores.bFBackpack.backpack.BackpackType"); assertEquals(4, type.getEnumConstants().length);
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.bukkit.Material"));
        }
    }
}
