package top.mores.bFBackpack.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.mores.bFBackpack.service.*;
import java.util.*;
import java.util.concurrent.*;

/** Fresh main-thread permission snapshots; close completes callbacks cancelled by plugin disable. */
public final class BukkitPermissionGateway implements PermissionGateway, AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<CompletableFuture<PermissionSnapshot>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public BukkitPermissionGateway(JavaPlugin plugin) { this.plugin = plugin; }
    @Override public CompletableFuture<PermissionSnapshot> capture(UUID uuid, Set<String> nodes) {
        CompletableFuture<PermissionSnapshot> future = new CompletableFuture<>();
        pending.add(future);
        future.whenComplete((v, e) -> pending.remove(future));
        if (closed) { future.completeExceptionally(new IllegalStateException("Plugin disabled")); return future; }
        Runnable capture = () -> {
            if (closed) { future.completeExceptionally(new IllegalStateException("Plugin disabled")); return; }
            Player player = Bukkit.getPlayer(uuid);
            Set<String> granted = new HashSet<>();
            if (player != null && player.isOnline()) {
                for (String node : nodes) if (player.hasPermission(node)) granted.add(node);
            }
            future.complete(new PermissionSnapshot(uuid, player != null && player.isOnline(), granted,
                    System.currentTimeMillis(), 0));
        };
        try {
            if (Bukkit.isPrimaryThread()) capture.run();
            else Bukkit.getScheduler().runTask(plugin, capture);
        } catch (RuntimeException e) { future.completeExceptionally(e); }
        return future;
    }
    @Override public void close() {
        closed = true;
        for (var future : pending) future.completeExceptionally(new IllegalStateException("Plugin disabled"));
        pending.clear();
    }
}
