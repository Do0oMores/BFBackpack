package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {
    @TempDir Path folder;
    WeaponCatalog catalog;
    List<String> diagnostics;
    @BeforeEach void setup() throws Exception {
        BackendFixture.copyResources(folder); diagnostics = new ArrayList<>();
        catalog = new WeaponCatalog(diagnostics::add); catalog.reload(folder);
    }
    void replace(String file, String from, String to) throws IOException {
        Path path = folder.resolve(file); Files.writeString(path, Files.readString(path).replace(from, to));
    }
    @Test void loadsAndFiltersNormalizedTagsAndIndependentSlots() {
        var c = catalog.snapshot(); assertEquals(16, c.weapons().size()); assertEquals(3, c.attachments().size()); assertTrue(diagnostics.isEmpty());
        assertEquals(List.of("battlefield:stg44"), c.filter(BackpackType.ASSAULT, LoadoutSlot.PRIMARY, Set.of("BATTLEFIELD:ASSAULT_RIFLE")).stream().map(WeaponDefinition::id).toList());
        assertTrue(c.filter(BackpackType.MEDIC, LoadoutSlot.PRIMARY, Set.of()).stream().noneMatch(w -> w.id().equals("battlefield:stg44")));
        assertThrows(IllegalArgumentException.class, () -> c.filter(null, null, Set.of("battlefield:unknown")));
    }
    @Test void exactDuplicateYamlKeysRejectReloadAndKeepOldSnapshot() throws Exception {
        var previous = catalog.snapshot();
        Files.writeString(folder.resolve("weapons.yml"), Files.readString(folder.resolve("weapons.yml")) + "\n  battlefield:stg44: {}\n");
        assertThrows(IOException.class, () -> catalog.reload(folder)); assertSame(previous, catalog.snapshot());
    }
    @Test void normalizedDuplicateIdQuarantinesBothDefinitions() throws Exception {
        Files.writeString(folder.resolve("weapons.yml"), Files.readString(folder.resolve("weapons.yml")) + "\n  BATTLEFIELD:STG44: {}\n");
        catalog.reload(folder); assertFalse(catalog.snapshot().weapons().containsKey("battlefield:stg44"));
        assertTrue(diagnostics.stream().anyMatch(s -> s.contains("Duplicate normalized")));
    }
    @Test void unknownWeaponTagQuarantinesOnlyThatWeapon() throws Exception {
        replace("weapons.yml", "tags: [battlefield:smg]", "tags: [battlefield:does_not_exist]");
        catalog.reload(folder); assertEquals(15, catalog.snapshot().weapons().size());
        assertTrue(diagnostics.stream().anyMatch(s -> s.contains("unknown ID/TAG")));
    }
    @Test void invalidBooleanAndUnknownSlotsAreRejectedPerEntry() throws Exception {
        replace("weapons.yml", "attachments:\n      enabled: true", "attachments:\n      enabled: 'yes'");
        catalog.reload(folder); assertFalse(catalog.snapshot().weapons().containsKey("battlefield:stg44"));
        assertTrue(diagnostics.stream().anyMatch(s -> s.contains("must be boolean")));
    }
    @Test void malformedRulesAndUnknownTagsRetainPreviousSnapshot() throws Exception {
        var previous = catalog.snapshot(); replace("class-rules.yml", "allow-tags: [battlefield:smg]", "allow-tags: [battlefield:unknown]");
        assertThrows(IOException.class, () -> catalog.reload(folder)); assertSame(previous, catalog.snapshot());
    }
    @Test void unknownRuleSlotRejectsReload() throws Exception {
        replace("class-rules.yml", "    PRIMARY:", "    UNRECOGNIZED:");
        assertThrows(IOException.class, () -> catalog.reload(folder));
    }
    @Test void reloadIsAtomicAndBlacklistsOverrideWhitelist() throws Exception {
        replace("class-rules.yml", "deny-items: []", "deny-items: [battlefield:stg44]");
        long oldRevision = catalog.snapshot().revision(); catalog.reload(folder);
        assertEquals(oldRevision + 1, catalog.snapshot().revision());
        assertFalse(catalog.snapshot().rules().allows(BackpackType.ASSAULT, LoadoutSlot.PRIMARY, catalog.snapshot().weapons().get("battlefield:stg44")));
        assertNull(catalog.snapshot().rules().defaultItem(BackpackType.ASSAULT, LoadoutSlot.PRIMARY));
    }
    @Test void catalogAndDtoCollectionsAreImmutable() {
        var c = catalog.snapshot(); assertThrows(UnsupportedOperationException.class, () -> c.weapons().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.weapons().get("battlefield:stg44").tags().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.attachments().get("battlefield:scope_basic").allowedWeapons().clear());
    }
}
