package top.mores.bFBackpack.service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Permissions read on the Bukkit main thread for exactly one operation; never cached across requests.
 * <p>A snapshot is only a statement about one moment. {@link #capturedAt} lets the database worker reject
 * a snapshot that waited in a backlog, and {@link #generation} ties it to one join session so a quit and
 * rejoin can never reuse the previous session's grants.</p>
 *
 * @param playerId   player this snapshot was captured for
 * @param online     player was online at capture time; an offline snapshot authorizes nothing
 * @param granted    the requested nodes that were granted at capture time
 * @param capturedAt epoch millis at capture time
 * @param generation join generation this snapshot was requested for; zero when not bound yet
 */
public record PermissionSnapshot(UUID playerId, boolean online, Set<String> granted, long capturedAt, long generation) {

    public PermissionSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        granted = Set.copyOf(granted);
    }

    public boolean has(String node) { return node.isEmpty() || granted.contains(node); }

    /** True when the snapshot already waited longer than the allowed maximum before being used. */
    public boolean staleAfter(long maxAgeMs) {
        return maxAgeMs > 0 && System.currentTimeMillis() - capturedAt > maxAgeMs;
    }

    /** Milliseconds elapsed since capture; may be negative only across a clock jump. */
    public long ageMs() { return System.currentTimeMillis() - capturedAt; }

    /** Bind this snapshot to a join generation without touching the captured permissions. */
    public PermissionSnapshot withGeneration(long generation) {
        return new PermissionSnapshot(playerId, online, granted, capturedAt, generation);
    }
}
