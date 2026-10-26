package top.mores.bFBackpack.api;

import java.util.Objects;

/**
 * Runtime verification, distinct from catalog legality. No adapter means UNVERIFIED.
 * <p>{@code detail} carries the failure classification (for example {@link ResultCode#RESOURCE_NOT_FOUND}
 * or {@link ResultCode#ATTACHMENT_INVALID}) so the deployment result can report a precise code instead
 * of one generic "provider unavailable". It is {@link ResultCode#OK} only for {@code VERIFIED}.</p>
 */
public record CompatibilityInfo(State state, ResultCode detail, String reason) {
    public enum State { VERIFIED, UNVERIFIED, REJECTED }

    public CompatibilityInfo {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
    }

    /** Convenience form: verified maps to OK, otherwise PROVIDER_UNAVAILABLE. */
    public CompatibilityInfo(State state, String reason) {
        this(state, state == State.VERIFIED ? ResultCode.OK : ResultCode.PROVIDER_UNAVAILABLE, reason);
    }

    /** True only after the installed provider has verified this exact description. */
    public boolean deployable() { return state == State.VERIFIED; }

    /** Safe default when no runtime validator is installed. */
    public static CompatibilityInfo unavailable() {
        return new CompatibilityInfo(State.UNVERIFIED, ResultCode.PROVIDER_UNAVAILABLE, "Runtime adapter not installed");
    }

    /** This exact resource description has been confirmed by the adapter. */
    public static CompatibilityInfo verified(String reason) {
        return new CompatibilityInfo(State.VERIFIED, ResultCode.OK, reason);
    }

    /** The resource exists but this exact combination is not valid. */
    public static CompatibilityInfo rejected(ResultCode detail, String reason) {
        return new CompatibilityInfo(State.REJECTED, detail == ResultCode.OK ? ResultCode.ATTACHMENT_INVALID : detail, reason);
    }

    /** The adapter could not reach a conclusion; never deployable. */
    public static CompatibilityInfo unverified(ResultCode detail, String reason) {
        return new CompatibilityInfo(State.UNVERIFIED, detail == ResultCode.OK ? ResultCode.PROVIDER_UNAVAILABLE : detail, reason);
    }
}
