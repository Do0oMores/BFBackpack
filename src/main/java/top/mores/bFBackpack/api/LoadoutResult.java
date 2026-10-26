package top.mores.bFBackpack.api;

import java.util.Objects;

/** Result of an asynchronous operation. Failure values may contain a diagnostic snapshot. */
public record LoadoutResult<T>(ResultCode code, T value, String message) {
    public LoadoutResult { Objects.requireNonNull(code); Objects.requireNonNull(message); }
    /** Whether the requested operation succeeded. */
    public boolean successful() { return code == ResultCode.OK; }
    /** Construct a successful result. */
    public static <T> LoadoutResult<T> ok(T value) { return new LoadoutResult<>(ResultCode.OK, value, "OK"); }
    /** Construct a failure without a value. */
    public static <T> LoadoutResult<T> fail(ResultCode code, String message) { return new LoadoutResult<>(code, null, message); }
}
