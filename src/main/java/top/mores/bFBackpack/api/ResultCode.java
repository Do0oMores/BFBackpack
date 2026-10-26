package top.mores.bFBackpack.api;

/** Machine-readable results; messages are diagnostic and must not be parsed. */
public enum ResultCode {
    OK, NOT_READY, NOT_FOUND, LOCKED, CLASS_DENIED, PERMISSION_DENIED,
    ATTACHMENT_INVALID, REVISION_CONFLICT, DATABASE_ERROR, PROVIDER_UNAVAILABLE,
    API_INCOMPATIBLE, INVALID_INPUT, CONFIGURATION_ERROR
}
