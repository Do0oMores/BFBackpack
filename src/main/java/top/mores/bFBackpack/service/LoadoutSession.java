package top.mores.bFBackpack.service;

import top.mores.bFBackpack.storage.LoadoutPlayerData;

/** A unique join generation. Data is published only after database confirmation. */
final class LoadoutSession {
    volatile LoadoutPlayerData confirmed;
}
