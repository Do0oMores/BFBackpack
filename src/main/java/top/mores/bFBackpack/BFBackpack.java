package top.mores.bFBackpack;

import org.bukkit.plugin.java.JavaPlugin;

public final class BFBackpack extends JavaPlugin {

    private static BFBackpack instance;

    @Override
    public void onEnable() {
        getLogger().info("Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled");
    }

    public static BFBackpack getInstance(){
        return instance;
    }
}
