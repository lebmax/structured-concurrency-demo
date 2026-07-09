package dev.axiom.scdemo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ExecutorServiceDemo {
    private static final Instant STARTED_AT = Instant.now();
    private static final Duration SHORT_PAUSE = Duration.ofMillis(120);

    private ExecutorServiceDemo() {
    }

    static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);

        switch (scenario) {
            case "all" -> runAll();
            case "all-success" -> runAllSuccess();
            case "fail-fast" -> runFailFast();
            case "race" -> runRace();
            case "timeout" -> runTimeout();
            case "quorum" -> runQuorum();
            case "help", "--help", "-h" -> printUsage();
            default -> {
                System.err.println("Unknown scenario: " + scenario);
                printUsage();
                System.exit(2);
            }
        }
    }

    private static void runAll() throws InterruptedException {
        runAllSuccess();
        pause();
        runFailFast();
        pause();
        runRace();
        pause();
        runTimeout();
        pause();
        runQuorum();
    }

    private static void runAllSuccess() {
        heading("legacy all-success: aggregate a profile with ExecutorService");

        ExecutorService executor = newFixedExecutor("legacy-profile-", 3);
        List<Future<ProfilePart>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> fetchProfilePart("account", Duration.ofMillis(220), "id=42,tier=pro")));
            futures.add(executor.submit(() -> fetchProfilePart("orders", Duration.ofMillis(330), "last=2026-07-08,total=17")));
            futures.add(executor.submit(() -> fetchProfilePart("recommendations", Duration.ofMillis(180), "items=3")));

            List<ProfilePart> parts = collectAllOrCancel(futures);
            Map<String, String> profile = parts.stream()
                    .collect(Collectors.toMap(ProfilePart::source, ProfilePart::value));

            log("profile assembled: " + profile);
        } catch (ExecutionException ex) {
            log("operation failed with cause: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            log("owner thread interrupted");
        } finally {
            closeExecutor(executor);
        }
    }

    private static void runFailFast() {
        heading("legacy fail-fast: manual cancellation after first failed Future");

        ExecutorService executor = newFixedExecutor("legacy-fail-fast-", 3);
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
        List<Future<String>> futures = submitAll(completion, List.of(
                () -> successfulCall("billing", Duration.ofMillis(700), "billing-ok"),
                () -> failingCall("fraud-check", Duration.ofMillis(160),
                        new IllegalStateException("fraud service rejected the request")),
                () -> successfulCall("email-render", Duration.ofMillis(900), "email-ok")));

        try {
            for (int completed = 0; completed < futures.size(); completed++) {
                Future<String> future = completion.take();
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    cancelAll(futures);
                    log("operation failed with cause: " + describe(ex.getCause()));
                    return;
                }
            }
            log("all tasks completed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            log("owner thread interrupted");
        } finally {
            closeExecutor(executor);
        }
    }

    private static void runRace() {
        heading("legacy race: first successful Future wins");

        ExecutorService executor = newFixedExecutor("legacy-race-", 3);
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
        List<Future<String>> futures = submitAll(completion, List.of(
                () -> failingCall("cache-a", Duration.ofMillis(90),
                        new IllegalStateException("stale cache entry")),
                () -> successfulCall("replica-eu", Duration.ofMillis(180), "response-from-eu"),
                () -> successfulCall("replica-us", Duration.ofMillis(520), "response-from-us")));
        List<Throwable> failures = new ArrayList<>();

        try {
            for (int completed = 0; completed < futures.size(); completed++) {
                Future<String> future = completion.take();
                try {
                    String winner = future.get();
                    log("winner: " + winner);
                    return;
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }

            log("all candidates failed: " + failures.stream()
                    .map(ExecutorServiceDemo::describe)
                    .collect(Collectors.joining("; ")));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        } finally {
            cancelAll(futures);
            closeExecutor(executor);
        }
    }

    private static void runTimeout() {
        heading("legacy timeout: invokeAll timeout plus manual result inspection");

        ExecutorService executor = newFixedExecutor("legacy-timeout-", 2);
        List<Callable<String>> tasks = List.of(
                () -> successfulCall("fast-index", Duration.ofMillis(110), "index-ok"),
                () -> successfulCall("slow-warehouse", Duration.ofMillis(800), "warehouse-ok"));

        try {
            List<Future<String>> futures = executor.invokeAll(tasks, 260, TimeUnit.MILLISECONDS);
            List<String> results = new ArrayList<>();
            boolean timedOut = false;

            for (Future<String> future : futures) {
                if (future.isCancelled()) {
                    timedOut = true;
                    continue;
                }
                results.add(future.get());
            }

            if (timedOut) {
                log("operation timed out; unfinished tasks were cancelled");
            } else {
                log("results: " + results);
            }
        } catch (ExecutionException ex) {
            log("operation failed with cause: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        } finally {
            closeExecutor(executor);
        }
    }

    private static void runQuorum() {
        heading("legacy quorum: manual success counting and cancellation");

        List<ReplicaPlan> replicas = List.of(
                new ReplicaPlan("replica-a", Duration.ofMillis(120), true),
                new ReplicaPlan("replica-b", Duration.ofMillis(230), true),
                new ReplicaPlan("replica-c", Duration.ofMillis(90), false),
                new ReplicaPlan("replica-d", Duration.ofMillis(700), true));
        int requiredSuccesses = 2;

        ExecutorService executor = newFixedExecutor("legacy-quorum-", replicas.size());
        ExecutorCompletionService<ReplicaReply> completion = new ExecutorCompletionService<>(executor);
        List<Future<ReplicaReply>> futures = new ArrayList<>();
        for (ReplicaPlan replica : replicas) {
            futures.add(completion.submit(() -> queryReplica(replica)));
        }

        List<ReplicaReply> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        try {
            for (int completed = 0; completed < futures.size(); completed++) {
                Future<ReplicaReply> future = completion.take();
                try {
                    successes.add(future.get());
                    if (successes.size() >= requiredSuccesses) {
                        log("quorum replies: " + successes);
                        return;
                    }
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                    int maxPossibleSuccesses = replicas.size() - failures.size();
                    if (maxPossibleSuccesses < requiredSuccesses) {
                        log("quorum failed: required=" + requiredSuccesses + ", actual=" + successes.size());
                        return;
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        } finally {
            cancelAll(futures);
            closeExecutor(executor);
        }
    }

    private static <T> List<T> collectAllOrCancel(List<Future<T>> futures)
            throws ExecutionException, InterruptedException {
        List<T> results = new ArrayList<>(futures.size());
        try {
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (ExecutionException | InterruptedException ex) {
            cancelAll(futures);
            throw ex;
        }
    }

    private static <T> List<Future<T>> submitAll(ExecutorCompletionService<T> completion, List<Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(completion.submit(task));
        }
        return futures;
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static ExecutorService newFixedExecutor(String namePrefix, int threads) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> new Thread(task, namePrefix + sequence.getAndIncrement());
        return Executors.newFixedThreadPool(threads, factory);
    }

    private static void closeExecutor(ExecutorService executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log("executor did not terminate within 2 s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("interrupted while waiting for executor shutdown");
        }
    }

    private static ProfilePart fetchProfilePart(String source, Duration delay, String value)
            throws InterruptedException {
        String result = successfulCall(source, delay, value);
        return new ProfilePart(source, result);
    }

    private static String successfulCall(String name, Duration delay, String result) throws InterruptedException {
        return simulatedCall(name, delay, () -> result);
    }

    private static String failingCall(String name, Duration delay, RuntimeException failure)
            throws InterruptedException {
        return simulatedCall(name, delay, () -> {
            throw failure;
        });
    }

    private static ReplicaReply queryReplica(ReplicaPlan plan) throws InterruptedException {
        if (plan.successful()) {
            String payload = successfulCall(plan.name(), plan.delay(), "payload-" + plan.name());
            return new ReplicaReply(plan.name(), payload);
        }
        failingCall(plan.name(), plan.delay(), new IllegalStateException(plan.name() + " returned 503"));
        throw new AssertionError("unreachable");
    }

    private static <T> T simulatedCall(String name, Duration delay, Supplier<T> resultSupplier)
            throws InterruptedException {
        log(name + " started");
        try {
            Thread.sleep(delay);
            T result = resultSupplier.get();
            log(name + " completed");
            return result;
        } catch (InterruptedException ex) {
            log(name + " interrupted");
            throw ex;
        } catch (RuntimeException ex) {
            log(name + " failed: " + ex.getMessage());
            throw ex;
        }
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void log(String message) {
        Thread thread = Thread.currentThread();
        String threadName = thread.getName();
        if (threadName == null || threadName.isBlank()) {
            threadName = "(unnamed)";
        }
        String kind = thread.isVirtual() ? "virtual" : "platform";
        long elapsedMillis = Duration.between(STARTED_AT, Instant.now()).toMillis();
        System.out.printf("[%5d ms] [%-22s %-8s] %s%n", elapsedMillis, threadName, kind, message);
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(SHORT_PAUSE);
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  run-legacy.ps1 all");
        System.out.println("  run-legacy.ps1 all-success");
        System.out.println("  run-legacy.ps1 fail-fast");
        System.out.println("  run-legacy.ps1 race");
        System.out.println("  run-legacy.ps1 timeout");
        System.out.println("  run-legacy.ps1 quorum");
    }

    private record ProfilePart(String source, String value) {
    }

    private record ReplicaPlan(String name, Duration delay, boolean successful) {
    }

    private record ReplicaReply(String replica, String payload) {
    }
}

