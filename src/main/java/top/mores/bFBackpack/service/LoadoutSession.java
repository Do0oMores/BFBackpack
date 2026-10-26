package top.mores.bFBackpack.service;

import top.mores.bFBackpack.storage.LoadoutPlayerData;

/**
 * A unique join generation. Data is published only after database confirmation.
 * <p>The generation is stamped into permission snapshots and deployment bindings so a description can
 * never be assembled from two different sessions.</p>
 */
final class LoadoutSession {
    final long generation;
    volatile LoadoutPlayerData confirmed;

    LoadoutSession(long generation) { this.generation = generation; }
}
