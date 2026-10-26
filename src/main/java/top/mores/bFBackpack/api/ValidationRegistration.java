package top.mores.bFBackpack.api;

/**
 * Handle for one installed {@link ValidationProvider}.
 * <p>The handle is identity-bound: closing it never unregisters a later registration, even when the new
 * registration reuses the same provider ID. {@link #close()} is idempotent and safe to call from any
 * thread. Adapters should call it from their plugin's {@code onDisable()} so the bridge classloader is
 * released; a Bukkit bridge that registers through the plugin entry point is unregistered
 * automatically on {@code PluginDisableEvent}.</p>
 */
public interface ValidationRegistration extends AutoCloseable {

    /** Provider ID this handle was registered with. */
    String providerId();

    /** Monotonic registration generation; increases on every register call. */
    long generation();

    /** True while this exact handle is the active registration. */
    boolean active();

    /** Unregister this handle only. Idempotent; a stale handle is a no-op. */
    @Override void close();
}
