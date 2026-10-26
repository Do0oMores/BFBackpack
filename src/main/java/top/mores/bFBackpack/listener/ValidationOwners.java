package top.mores.bFBackpack.listener;

import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import top.mores.bFBackpack.api.ValidationRegistration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds each runtime validator registration to the plugin that registered it and drops it when that
 * plugin disables.
 * <p>Only plugin names and registrations are retained, never the {@link Plugin} object itself, so a
 * disabled bridge plugin and its ClassLoader can be collected as soon as its registration is closed.
 * A bridge that cannot be identified this way must close its own {@link ValidationRegistration} in
 * {@code onDisable()}.</p>
 */
public final class ValidationOwners implements Listener, AutoCloseable {
    private final Map<String, List<ValidationRegistration>> owned = new ConcurrentHashMap<>();

    /** Record ownership; the registration itself stays owned by the engine. */
    public void bind(Plugin owner, ValidationRegistration registration) { bind(owner.getName(), registration); }

    /** Owner-name form, usable without a live Bukkit server. */
    public void bind(String ownerName, ValidationRegistration registration) {
        List<ValidationRegistration> registrations = owned.computeIfAbsent(ownerName, name -> new java.util.concurrent.CopyOnWriteArrayList<>());
        registrations.removeIf(candidate -> !candidate.active());
        registrations.add(registration);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) { release(event.getPlugin().getName()); }

    /** Close every registration owned by that plugin; returns how many were still active. */
    public int release(String pluginName) {
        List<ValidationRegistration> registrations = owned.remove(pluginName);
        if (registrations == null) return 0;
        int closed = 0;
        for (ValidationRegistration registration : registrations) {
            if (!registration.active()) continue;
            registration.close();
            closed++;
        }
        return closed;
    }

    /** Close every tracked registration. */
    public int releaseAll() {
        int closed = 0;
        for (String name : List.copyOf(owned.keySet())) closed += release(name);
        return closed;
    }

    /** Number of plugins currently holding a registration. */
    public int owners() { return owned.size(); }

    @Override public void close() { releaseAll(); }
}
