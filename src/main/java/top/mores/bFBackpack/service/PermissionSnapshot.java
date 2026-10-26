package top.mores.bFBackpack.service;

import java.util.Set;

/** Permissions captured on the Bukkit thread for one operation; never cached across requests. */
public record PermissionSnapshot(boolean online, Set<String> granted) {
    public PermissionSnapshot { granted = Set.copyOf(granted); }
    public boolean has(String node) { return node.isEmpty() || granted.contains(node); }
}
