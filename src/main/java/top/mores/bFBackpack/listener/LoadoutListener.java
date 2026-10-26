package top.mores.bFBackpack.listener;

import org.bukkit.event.*;
import org.bukkit.event.player.*;
import top.mores.bFBackpack.service.LoadoutService;

/** Only lifecycle data operations: no inventory, respawn or deployment side effects. */
public final class LoadoutListener implements Listener {
    private final LoadoutService service;
    public LoadoutListener(LoadoutService service) { this.service = service; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { service.loadPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { service.unloadPlayer(event.getPlayer().getUniqueId()); }
}
