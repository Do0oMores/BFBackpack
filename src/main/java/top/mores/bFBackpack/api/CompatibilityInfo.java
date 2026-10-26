package top.mores.bFBackpack.api;

import java.util.Objects;

/** Runtime verification, distinct from catalog legality. No adapter means UNVERIFIED. */
public record CompatibilityInfo(State state, String reason) {
    public enum State { VERIFIED, UNVERIFIED, REJECTED }
    public CompatibilityInfo { Objects.requireNonNull(state); Objects.requireNonNull(reason); }
    /** True only after the installed provider has verified this exact description. */
    public boolean deployable() { return state == State.VERIFIED; }
    /** Safe default when no runtime validator is installed. */
    public static CompatibilityInfo unavailable() { return new CompatibilityInfo(State.UNVERIFIED, "Runtime adapter not installed"); }
}
