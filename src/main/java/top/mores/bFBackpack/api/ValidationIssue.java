package top.mores.bFBackpack.api;

/** Why a saved slot is invalid. Records remain stored; fallbackId is the effective replacement. */
public record ValidationIssue(LoadoutSlot slot, String savedId, ResultCode code,
                              String reason, String fallbackId) {}
