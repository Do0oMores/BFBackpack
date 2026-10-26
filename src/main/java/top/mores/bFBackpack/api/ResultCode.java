package top.mores.bFBackpack.api;

/**
 * Machine-readable results; messages are diagnostic and must not be parsed.
 * <p>Deployment distinguishes catalog, authorization, runtime-resource and scheduling failures so a
 * Battlefield GUI can react without parsing strings.</p>
 */
public enum ResultCode {
    OK, NOT_READY, NOT_FOUND, LOCKED, CLASS_DENIED, PERMISSION_DENIED,
    ATTACHMENT_INVALID, REVISION_CONFLICT, DATABASE_ERROR, PROVIDER_UNAVAILABLE,
    API_INCOMPATIBLE, INVALID_INPUT, CONFIGURATION_ERROR,
    /** The saved loadout cannot be resolved into a complete deployment description. */
    LOADOUT_INVALID,
    /** A provider reported that the real gun/attachment resource does not exist. */
    RESOURCE_NOT_FOUND,
    /** Runtime verification did not finish inside runtime-validation.timeout-ms. */
    VALIDATION_TIMEOUT,
    /** The validation pool is saturated; nothing was executed and nothing was authorized. */
    VALIDATION_BUSY,
    /** The captured permission snapshot aged out before the operation could run; re-read and retry. */
    PERMISSION_STALE
}
