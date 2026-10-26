package top.mores.bFBackpack.api;

/** Access to an item is independent of class legality. permanent survives permission revocation. */
public record UnlockResult(String itemId, boolean unlocked, boolean permanent, String source) {}
