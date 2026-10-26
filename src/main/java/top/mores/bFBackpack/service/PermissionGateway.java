package top.mores.bFBackpack.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Thread boundary: implementations must inspect Bukkit permissions only on the server thread. */
@FunctionalInterface
public interface PermissionGateway {
    CompletableFuture<PermissionSnapshot> capture(UUID player, Set<String> nodes);
}
