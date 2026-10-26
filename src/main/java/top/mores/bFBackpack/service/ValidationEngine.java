package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded execution environment for external runtime verification, plus the provider registry.
 *
 * <h2>Why a separate pool</h2>
 * The ordered database worker must never run or wait for Forge/TaCZ code. Phase one reads the database
 * and returns an immutable {@link DeploymentPlan}; phase two runs here. A slow, hanging or throwing
 * adapter can therefore only consume one bounded validation slot, never the database queue.
 *
 * <h2>Capacity and timeouts</h2>
 * A fixed pool of {@code max-concurrent-validations} daemon threads with a bounded queue rejects excess
 * work with {@link ResultCode#VALIDATION_BUSY}; a single timer thread completes overdue requests with
 * {@link ResultCode#VALIDATION_TIMEOUT}. No request creates a thread or an unbounded queue.
 *
 * <h2>Registration</h2>
 * Exactly one provider is active. {@link #register} replaces it atomically (the previous handle becomes
 * inactive), {@link ValidationRegistration#close()} removes only its own registration, and
 * {@link #close()} drops every reference so a disabled bridge plugin can be unloaded.
 */
public final class ValidationEngine implements AutoCloseable {

    /** Bounded runtime-validation settings; invalid values are clamped instead of failing startup. */
    public record Settings(int timeoutMs, int maxConcurrent, int queueCapacity) {
        public static final Settings DEFAULT = new Settings(3000, 8, 32);
        public Settings {
            timeoutMs = Math.min(120_000, Math.max(50, timeoutMs));
            maxConcurrent = Math.min(64, Math.max(1, maxConcurrent));
            queueCapacity = Math.min(4096, Math.max(1, queueCapacity));
        }
    }

    private final Settings settings;
    private final Logger logger;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timeouts;
    private final AtomicLong generations = new AtomicLong();
    private final AtomicReference<Registration> registration = new AtomicReference<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger outstanding = new AtomicInteger();
    private volatile boolean closed;

    public ValidationEngine(Settings settings, Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.workers = new ThreadPoolExecutor(settings.maxConcurrent(), settings.maxConcurrent(),
                30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(settings.queueCapacity()),
                runnable -> daemon(runnable, "BFBackpack-Validation"), new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
        this.timeouts = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "BFBackpack-Validation-Timeout"));
        this.timeouts.setRemoveOnCancelPolicy(true);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public Settings settings() { return settings; }

    /** Install or replace the single runtime provider. */
    public ValidationRegistration register(String providerId, ValidationProvider provider) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(provider, "provider");
        String id = providerId.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.-]{1,48}")) throw new IllegalArgumentException("Invalid provider ID: " + providerId);
        if (closed) throw new IllegalStateException("Validation engine is closed");
        int declared = provider.apiVersion();
        if (declared != BFBackpackAPI.API_VERSION)
            throw new IllegalArgumentException("API_INCOMPATIBLE: provider " + id + " targets API " + declared
                    + ", expected " + BFBackpackAPI.API_VERSION);
        Registration next = new Registration(id, generations.incrementAndGet(), provider);
        Registration previous = registration.getAndSet(next);
        if (previous != null) logger.info("运行时校验器已替换: " + previous.providerId + " -> " + id);
        return next;
    }

    /** Active provider ID, empty when none is installed. */
    public String providerId() {
        Registration current = registration.get();
        return current == null ? "" : current.providerId;
    }

    /** Generation of the active registration, zero when none is installed. */
    public long providerGeneration() {
        Registration current = registration.get();
        return current == null ? 0 : current.generation;
    }

    public boolean hasProvider() { return registration.get() != null; }

    /** Number of validations currently executing; exposed for tests and diagnostics. */
    public int activeValidations() { return workers.getActiveCount(); }

    /** Number of validations waiting for a free slot. */
    public int queuedValidations() { return workers.getQueue().size(); }

    /** Requests submitted but not yet settled; bounded by max-concurrent-validations + queue-capacity. */
    public int outstandingValidations() { return outstanding.get(); }

    /**
     * Run phase two for one deployment plan. The returned future always produces a description, including
     * on timeout, saturation and adapter failure, and completes on a validation or timer thread.
     */
    CompletableFuture<LoadoutResult<DeploymentLoadout>> validate(DeploymentPlan plan) {
        Registration accepted = registration.get();
        ConcurrentHashMap<LoadoutSlot, CompatibilityInfo> finished = new ConcurrentHashMap<>();
        String label = "deployment:" + plan.playerId() + "/" + plan.soldierClass();
        CompletableFuture<Map<LoadoutSlot, CompatibilityInfo>> work = submit(label, () -> plan
                .verify(accepted == null ? null : accepted.provider, finished::put)
                .thenApply(ignored -> Map.copyOf(finished)));
        return work.handle((compatibility, error) -> settle(plan, accepted, compatibility, error));
    }

    private LoadoutResult<DeploymentLoadout> settle(DeploymentPlan plan, Registration accepted,
            Map<LoadoutSlot, CompatibilityInfo> finished, Throwable error) {
        ResultCode cause = classify(error);
        Map<LoadoutSlot, CompatibilityInfo> resolved = new EnumMap<>(LoadoutSlot.class);
        for (DeploymentPlan.Slot slot : plan.slots()) {
            CompatibilityInfo info = finished == null ? null : finished.get(slot.slot());
            if (info == null) {
                info = cause == null
                        ? CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, "Runtime verification returned no result")
                        : CompatibilityInfo.unverified(cause, explain(cause));
            }
            resolved.put(slot.slot(), info);
        }
        boolean live = plan.sessionLive();
        boolean current = live && plan.stillCurrent();
        ResultCode status = plan.status(resolved, cause, live, current);
        DeploymentLoadout description = plan.describe(resolved, status,
                accepted == null ? "" : accepted.providerId, accepted == null ? 0 : accepted.generation, current);
        if (status == ResultCode.OK) return LoadoutResult.ok(description);
        return new LoadoutResult<>(status, description, explain(status));
    }

    private String explain(ResultCode status) {
        return switch (status) {
            case OK -> "OK";
            case VALIDATION_TIMEOUT -> "运行时校验在 " + settings.timeoutMs() + "ms 内未完成，资源保持未验证";
            case VALIDATION_BUSY -> "校验队列已满，请稍后重试";
            case PROVIDER_UNAVAILABLE -> "未安装运行时校验器或资源无法验证";
            case LOADOUT_INVALID -> "至少一个槽位没有可用的有效装备";
            case REVISION_CONFLICT -> "校验期间配装/改装/目录已变更，请重新请求部署描述";
            case RESOURCE_NOT_FOUND -> "提供方不存在该枪械或附件资源";
            case ATTACHMENT_INVALID -> "该附件组合在提供方处不兼容";
            case PERMISSION_DENIED -> "缺少该装备所需的权限";
            case PERMISSION_STALE -> "权限快照已过期，请重新请求";
            case NOT_READY -> "后端已停用或会话已失效";
            default -> "无法部署: " + status;
        };
    }

    private static ResultCode classify(Throwable error) {
        if (error == null) return null;
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof TimeoutException) return ResultCode.VALIDATION_TIMEOUT;
        if (cause instanceof RejectedExecutionException) return ResultCode.VALIDATION_BUSY;
        if (cause instanceof IllegalStateException) return ResultCode.NOT_READY;
        return ResultCode.PROVIDER_UNAVAILABLE;
    }

    private <T> CompletableFuture<T> submit(String label, Callable<CompletableFuture<T>> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IllegalStateException("Validation engine is closed"));
            return result;
        }
        pending.add(result);
        outstanding.incrementAndGet();
        result.whenComplete((value, error) -> { pending.remove(result); outstanding.decrementAndGet(); });
        // Bound the number of outstanding requests, not only the number of adapter invocations: an async
        // adapter frees its pool thread immediately, so the queue alone would not limit pending work.
        if (outstanding.get() > settings.maxConcurrent() + settings.queueCapacity()) {
            result.completeExceptionally(new RejectedExecutionException("Too many outstanding validations: " + label));
            return result;
        }
        ScheduledFuture<?> deadline = scheduleDeadline(label, result);
        try {
            workers.execute(() -> {
                CompletableFuture<T> work;
                try { work = task.call(); }
                catch (Throwable failure) { complete(result, deadline, null, failure); return; }
                work.whenComplete((value, error) -> complete(result, deadline, value, error));
            });
        } catch (RejectedExecutionException saturated) {
            complete(result, deadline, null, saturated);
        } catch (Exception unexpected) {
            complete(result, deadline, null, unexpected);
        }
        return result;
    }

    private static <T> void complete(CompletableFuture<T> result, ScheduledFuture<?> deadline, T value, Throwable error) {
        if (deadline != null) deadline.cancel(false);
        if (error != null) result.completeExceptionally(error);
        else result.complete(value);
    }

    private <T> ScheduledFuture<?> scheduleDeadline(String label, CompletableFuture<T> result) {
        try {
            return timeouts.schedule(() -> result.completeExceptionally(
                    new TimeoutException("Runtime validation exceeded " + settings.timeoutMs() + "ms: " + label)),
                    settings.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) {
            return null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Registration previous = registration.getAndSet(null);
        if (previous != null) logger.info("运行时校验器已卸载: " + previous.providerId);
        timeouts.shutdownNow();
        for (CompletableFuture<?> future : pending)
            future.completeExceptionally(new IllegalStateException("Validation engine is closed"));
        pending.clear();
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(1, TimeUnit.SECONDS))
                logger.log(Level.FINE, "仍有运行时校验任务未响应中断，线程为 daemon");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Identity-bound registration; closing it removes only this instance. */
    private final class Registration implements ValidationRegistration {
        private final String providerId;
        private final long generation;
        private final ValidationProvider provider;

        private Registration(String providerId, long generation, ValidationProvider provider) {
            this.providerId = providerId; this.generation = generation; this.provider = provider;
        }

        @Override public String providerId() { return providerId; }
        @Override public long generation() { return generation; }
        @Override public boolean active() { return !closed && registration.get() == this; }

        @Override public void close() {
            if (registration.compareAndSet(this, null))
                logger.info("运行时校验器已注销: " + providerId);
        }
    }
}
