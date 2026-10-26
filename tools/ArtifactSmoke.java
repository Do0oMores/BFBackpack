import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.WeaponCatalog;
import top.mores.bFBackpack.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.net.URLClassLoader;

/** Runs against the shaded plugin jar without any Bukkit dependency on the classpath. */
public final class ArtifactSmoke {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory(Path.of(args[1]), "artifact-smoke-");
        for (String name : List.of("weapons.yml", "attachments.yml", "class-rules.yml")) {
            try (var input = ArtifactSmoke.class.getResourceAsStream("/" + name)) { Files.copy(Objects.requireNonNull(input), folder.resolve(name)); }
        }
        WeaponCatalog catalog = new WeaponCatalog(message -> { throw new AssertionError(message); }); catalog.reload(folder);
        DbSettings settings = new DbSettings(false, folder.resolve("smoke.db").toString(), "", 0, "", "", "", "", "smoke_");
        UUID player = UUID.randomUUID();
        try (Database db = new Database(Logger.getLogger("artifact-smoke"), settings)) {
            db.executor().submit(() -> {
                db.connect(); var repo = new SqlLoadoutRepository(db); repo.initialize();
                if (repo.loadPlayer(player, catalog.snapshot()).loadouts().size() != 4) throw new AssertionError("Default initialization");
                repo.grantUnlock(player, "battlefield:gewehr43", "SMOKE");
                repo.saveCustomization(new WeaponCustomization(player, "battlefield:stg44", Map.of("SCOPE", "battlefield:scope_basic"), "", 0));
                return null;
            }).get(10, TimeUnit.SECONDS);
        }
        try (Database db = new Database(Logger.getLogger("artifact-smoke"), settings)) {
            db.executor().submit(() -> {
                db.connect(); var repo = new SqlLoadoutRepository(db); repo.initialize();
                var restored = repo.loadPlayer(player, catalog.snapshot());
                if (!restored.unlocks().contains("battlefield:gewehr43") || restored.customizations().get("battlefield:stg44").revision() != 1) throw new AssertionError("Restart persistence");
                return null;
            }).get(10, TimeUnit.SECONDS);
        }
        if (!new com.mysql.cj.jdbc.Driver().acceptsURL("jdbc:mysql://localhost/test")) throw new AssertionError("Packaged MySQL driver missing");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, null)) {
            Class<?> api = loader.loadClass("top.mores.bFBackpack.api.BFBackpackAPI");
            if (api.getDeclaredMethods().length < 20) throw new AssertionError("API jar incomplete");
            if (loader.loadClass("top.mores.bFBackpack.backpack.BackpackType").getEnumConstants().length != 4) throw new AssertionError("Class enum incomplete");
            try { loader.loadClass("org.bukkit.Material"); throw new AssertionError("API depends on Bukkit"); } catch (ClassNotFoundException expected) { }
        }
        System.out.println("ARTIFACT_SMOKE_OK: relocated YAML/JSON, shaded SQLite restart, MySQL driver, standalone API");
    }
}
