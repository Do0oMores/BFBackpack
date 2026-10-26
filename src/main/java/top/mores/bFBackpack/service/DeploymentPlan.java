package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Phase-one result: an immutable description of everything the database worker already confirmed.
 * <p>It carries the exact revisions the caller must bind to, so phase two can detect a concurrent edit
 * or catalog reload instead of mixing results from two versions. Nothing here touches provider code;
 * {@link #verify} only hands immutable records to the validation pool.</p>
 */
final class DeploymentPlan {

    /** One slot, already resolved from the catalog and the confirmed customization. */
    record Slot(LoadoutSlot slot, WeaponDefinition weapon, WeaponCustomization customization,
                Map<String, AttachmentDefinition> attachments, ResultCode rejection, String reason) {
        Slot { attachments = Map.copyOf(attachments); }
        boolean needsAttachmentCheck() {
            return weapon.attachmentsEnabled() || !customization.attachments().isEmpty() || !customization.skinId().isEmpty();
        }
    }

    private final UUID playerId;
    private final BackpackType soldierClass;
    private final LoadoutSession session;
    private final ClassLoadout saved;
    private final LoadoutSnapshot snapshot;
    private final Map<LoadoutSlot, Slot> slots;
    private final long catalogRevision;
    private final LongSupplier liveCatalogRevision;
    private final Map<String, Long> customizationRevisions;
    private final PermissionSnapshot permissions;
    private final ResultCode precheck;
    private final BooleanSupplier sessionLive;
    private final int protocolVersion;

    DeploymentPlan(UUID playerId, BackpackType soldierClass, LoadoutSession session, ClassLoadout saved,
            LoadoutSnapshot snapshot, Map<LoadoutSlot, Slot> slots, long catalogRevision,
            LongSupplier liveCatalogRevision, PermissionSnapshot permissions, ResultCode precheck,
            BooleanSupplier sessionLive, int protocolVersion) {
        this.playerId = playerId; this.soldierClass = soldierClass; this.session = session; this.saved = saved;
        this.snapshot = snapshot;
        Map<LoadoutSlot, Slot> ordered = new EnumMap<>(LoadoutSlot.class);
        ordered.putAll(slots);
        this.slots = Collections.unmodifiableMap(ordered);
        this.catalogRevision = catalogRevision; this.liveCatalogRevision = liveCatalogRevision;
        this.permissions = permissions; this.precheck = precheck; this.protocolVersion = protocolVersion;
        this.sessionLive = sessionLive;
        Map<String, Long> revisions = new HashMap<>();
        slots.values().forEach(slot -> {
            String id = slot.weapon().id();
            revisions.putIfAbsent(id, slot.customization().revision());
        });
        this.customizationRevisions = Map.copyOf(revisions);
    }

    UUID playerId() { return playerId; }
    BackpackType soldierClass() { return soldierClass; }
    PermissionSnapshot permissions() { return permissions; }
    Collection<Slot> slots() { return slots.values(); }

    /**
     * Verify every unresolved slot, reporting each result as soon as it arrives. Slots whose saved
     * customization is already invalid keep that code and are not sent to the provider. Never blocks:
     * if an adapter returns a future that never completes, only the engine deadline ends the request.
     * A plan whose authorization already expired verifies nothing and reports that code per slot.
     */
    CompletableFuture<Void> verify(ValidationProvider provider, BiConsumer<LoadoutSlot, CompatibilityInfo> sink) {
        if (precheck != null) {
            String reason = "Authorization snapshot no longer valid: " + precheck;
            for (Slot slot : slots.values()) sink.accept(slot.slot(), CompatibilityInfo.unverified(precheck, reason));
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.rejection() != null) {
                sink.accept(slot.slot(), CompatibilityInfo.rejected(slot.rejection(), slot.reason()));
                continue;
            }
            if (provider == null) { sink.accept(slot.slot(), CompatibilityInfo.unavailable()); continue; }
            pending.add(verifySlot(provider, slot).thenAccept(info -> sink.accept(slot.slot(), info)));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<CompatibilityInfo> verifySlot(ValidationProvider provider, Slot slot) {
        CompletableFuture<CompatibilityInfo> weapon;
        try {
            weapon = guard(provider.validateWeapon(slot.weapon()), "Weapon provider validation failed");
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failed("Weapon provider validation failed", e));
        }
        return weapon.thenCompose(info -> {
            if (!info.deployable() || !slot.needsAttachmentCheck()) return CompletableFuture.completedFuture(info);
            try {
                return guard(provider.validateAttachments(slot.weapon(), slot.customization()), "Attachment validation failed");
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(failed("Attachment validation failed", e));
            }
        });
    }

    private static CompletableFuture<CompatibilityInfo> guard(CompletableFuture<CompatibilityInfo> future, String label) {
        if (future == null) return CompletableFuture.completedFuture(CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, label + ": adapter returned null"));
        return future.handle((info, error) -> {
            if (error != null) return failed(label, error);
            return info == null ? CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, label + ": adapter returned null") : info;
        });
    }

    private static CompatibilityInfo failed(String label, Throwable error) {
        String detail = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().isBlank()) detail += ": " + error.getMessage();
        return CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, label + " (" + detail + ")");
    }

    /**
     * True only when nothing moved since phase one: same session, same class loadout revision, same
     * customization revisions and the same catalog snapshot.
     */
    boolean sessionLive() { return sessionLive.getAsBoolean(); }

    boolean stillCurrent() {
        if (!sessionLive.getAsBoolean() || session.confirmed == null) return false;
        ClassLoadout live = session.confirmed.loadouts().get(soldierClass);
        if (live == null || live.revision() != saved.revision()) return false;
        for (var entry : customizationRevisions.entrySet()) {
            WeaponCustomization current = session.confirmed.customizations().get(entry.getKey());
            long revision = current == null ? 0 : current.revision();
            if (revision != entry.getValue()) return false;
        }
        return liveCatalogRevision.getAsLong() == catalogRevision;
    }

    /** Deployment classification; the most severe per-slot finding wins. */
    ResultCode status(Map<LoadoutSlot, CompatibilityInfo> compatibility, ResultCode cause, boolean live, boolean current) {
        if (!live) return ResultCode.NOT_READY;
        if (!current) return ResultCode.REVISION_CONFLICT;
        if (slots.size() < LoadoutSlot.values().length) return ResultCode.LOADOUT_INVALID;
        ResultCode worst = precheck != null ? precheck : cause;
        for (Slot slot : slots.values()) {
            CompatibilityInfo info = compatibility.get(slot.slot());
            if (info == null || info.deployable()) continue;
            worst = pick(worst, info.detail());
        }
        return worst == null ? ResultCode.OK : worst;
    }

    /** Lower importance value wins, so a missing permission outranks an unverified resource. */
    private static ResultCode pick(ResultCode current, ResultCode candidate) {
        if (candidate == null) return current;
        return current == null || importance(candidate) < importance(current) ? candidate : current;
    }

    private static int importance(ResultCode code) {
        return switch (code) {
            case OK -> Integer.MAX_VALUE;
            case PERMISSION_STALE -> 0;
            case NOT_READY -> 1;
            case PERMISSION_DENIED -> 2;
            case CLASS_DENIED -> 3;
            case LOCKED -> 4;
            case LOADOUT_INVALID -> 5;
            case VALIDATION_TIMEOUT -> 6;
            case VALIDATION_BUSY -> 7;
            case RESOURCE_NOT_FOUND -> 8;
            case ATTACHMENT_INVALID -> 9;
            case NOT_FOUND -> 10;
            default -> 11;
        };
    }

    DeploymentLoadout describe(Map<LoadoutSlot, CompatibilityInfo> compatibility, ResultCode status,
            String providerId, long providerGeneration, boolean current) {
        Map<LoadoutSlot, DeploymentEquipment> equipment = new EnumMap<>(LoadoutSlot.class);
        List<ValidationIssue> issues = new ArrayList<>();
        for (Slot slot : slots.values()) {
            CompatibilityInfo info = compatibility.getOrDefault(slot.slot(), CompatibilityInfo.unavailable());
            equipment.put(slot.slot(), new DeploymentEquipment(slot.weapon(), slot.customization(), slot.attachments(), info));
            if (!info.deployable())
                issues.add(new ValidationIssue(slot.slot(), slot.weapon().id(), info.detail(), info.reason(), null));
        }
        DeploymentBinding binding = new DeploymentBinding(playerId, soldierClass, saved.revision(), catalogRevision,
                permissions.generation(), permissions.capturedAt(), providerId, providerGeneration, current);
        return new DeploymentLoadout(protocolVersion, binding, snapshot, equipment, issues, status);
    }
}
