package dev.axiom.scdemo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class StructuredConcurrencyDemo {
    private static final Instant STARTED_AT = Instant.now();
    private static final Duration SHORT_PAUSE = Duration.ofMillis(120);

    private StructuredConcurrencyDemo() {
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

    private static void runAll() throws Exception {
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

    private static void runAllSuccess() throws InterruptedException {
        heading("all-success: aggregate a profile from independent services");

        ThreadFactory namedVirtualThreads = Thread.ofVirtual()
                .name("profile-loader-", 0)
                .factory();

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<ProfilePart>allSuccessfulOrThrow(),
                config -> config.withName("profile")
                        .withThreadFactory(namedVirtualThreads))) {

            scope.fork(() -> fetchProfilePart("account", Duration.ofMillis(220), "id=42,tier=pro"));
            scope.fork(() -> fetchProfilePart("orders", Duration.ofMillis(330), "last=2026-07-08,total=17"));
            scope.fork(() -> fetchProfilePart("recommendations", Duration.ofMillis(180), "items=3"));

            List<ProfilePart> parts = scope.join();
            Map<String, String> profile = parts.stream()
                    .collect(Collectors.toMap(ProfilePart::source, ProfilePart::value));

            log("profile assembled: " + profile);
        }
    }

    private static void runFailFast() {
        heading("fail-fast: one failing task cancels sibling tasks");

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>awaitAllSuccessfulOrThrow())) {

            scope.fork(() -> successfulCall("billing", Duration.ofMillis(700), "billing-ok"));
            scope.fork(() -> failingCall("fraud-check", Duration.ofMillis(160),
                    new IllegalStateException("fraud service rejected the request")));
            scope.fork(() -> successfulCall("email-render", Duration.ofMillis(900), "email-ok"));

            scope.join();
            log("this line is not expected");
        } catch (StructuredTaskScope.FailedException ex) {
            log("scope failed with cause: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        }
    }

    private static void runRace() {
        heading("race: first successful result wins");

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>anySuccessfulOrThrow())) {

            scope.fork(() -> failingCall("cache-a", Duration.ofMillis(90),
                    new IllegalStateException("stale cache entry")));
            scope.fork(() -> successfulCall("replica-eu", Duration.ofMillis(180), "response-from-eu"));
            scope.fork(() -> successfulCall("replica-us", Duration.ofMillis(520), "response-from-us"));

            String winner = scope.join();
            log("winner: " + winner);
        } catch (StructuredTaskScope.FailedException ex) {
            log("all candidates failed: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        }
    }

    private static void runTimeout() {
        heading("timeout: the whole scope has a deadline");

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>allSuccessfulOrThrow(),
                config -> config.withTimeout(Duration.ofMillis(260)))) {

            scope.fork(() -> successfulCall("fast-index", Duration.ofMillis(110), "index-ok"));
            scope.fork(() -> successfulCall("slow-warehouse", Duration.ofMillis(800), "warehouse-ok"));

            List<String> results = scope.join();
            log("results: " + results);
        } catch (StructuredTaskScope.TimeoutException ex) {
            log("scope timed out: " + ex.getClass().getSimpleName());
        } catch (StructuredTaskScope.FailedException ex) {
            log("scope failed with cause: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
        }
    }

    private static void runQuorum() {
        heading("quorum: custom Joiner cancels after two successful replicas");

        List<ReplicaPlan> replicas = List.of(
                new ReplicaPlan("replica-a", Duration.ofMillis(120), true),
                new ReplicaPlan("replica-b", Duration.ofMillis(230), true),
                new ReplicaPlan("replica-c", Duration.ofMillis(90), false),
                new ReplicaPlan("replica-d", Duration.ofMillis(700), true));

        var joiner = new FirstSuccessesJoiner<ReplicaReply>(2, replicas.size());

        try (var scope = StructuredTaskScope.open(joiner)) {
            for (ReplicaPlan replica : replicas) {
                scope.fork(() -> queryReplica(replica));
            }

            List<ReplicaReply> replies = scope.join();
            log("quorum replies: " + replies);
        } catch (StructuredTaskScope.FailedException ex) {
            log("quorum failed: " + describe(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("owner thread interrupted");
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
        System.out.println("  run.ps1 all");
        System.out.println("  run.ps1 all-success");
        System.out.println("  run.ps1 fail-fast");
        System.out.println("  run.ps1 race");
        System.out.println("  run.ps1 timeout");
        System.out.println("  run.ps1 quorum");
    }

    private record ProfilePart(String source, String value) {
    }

    private record ReplicaPlan(String name, Duration delay, boolean successful) {
    }

    private record ReplicaReply(String replica, String payload) {
    }

    private record OrderedSuccess<T>(int order, T value) {
    }

    private static final class FirstSuccessesJoiner<T> implements StructuredTaskScope.Joiner<T, List<T>> {
        private final int requiredSuccesses;
        private final int plannedSubtasks;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger successOrder = new AtomicInteger();
        private final List<OrderedSuccess<T>> successes = Collections.synchronizedList(new ArrayList<>());
        private final List<Throwable> failureCauses = Collections.synchronizedList(new ArrayList<>());

        private FirstSuccessesJoiner(int requiredSuccesses, int plannedSubtasks) {
            if (requiredSuccesses < 1) {
                throw new IllegalArgumentException("requiredSuccesses must be positive");
            }
            if (plannedSubtasks < requiredSuccesses) {
                throw new IllegalArgumentException("plannedSubtasks must be >= requiredSuccesses");
            }
            this.requiredSuccesses = requiredSuccesses;
            this.plannedSubtasks = plannedSubtasks;
        }

        @Override
        public boolean onComplete(StructuredTaskScope.Subtask<T> subtask) {
            int done = completed.incrementAndGet();

            if (subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                successes.add(new OrderedSuccess<>(successOrder.incrementAndGet(), subtask.get()));
                return successes.size() >= requiredSuccesses;
            }

            if (subtask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                failureCauses.add(subtask.exception());
                int failed = failures.incrementAndGet();
                int maxPossibleSuccesses = plannedSubtasks - failed;
                return maxPossibleSuccesses < requiredSuccesses || done == plannedSubtasks;
            }

            return done == plannedSubtasks;
        }

        @Override
        public List<T> result() {
            List<OrderedSuccess<T>> snapshot;
            synchronized (successes) {
                snapshot = new ArrayList<>(successes);
            }

            if (snapshot.size() >= requiredSuccesses) {
                return snapshot.stream()
                        .sorted((left, right) -> Integer.compare(left.order(), right.order()))
                        .limit(requiredSuccesses)
                        .map(OrderedSuccess::value)
                        .toList();
            }

            IllegalStateException error = new IllegalStateException(
                    "quorum was not reached: required=" + requiredSuccesses + ", actual=" + snapshot.size());
            synchronized (failureCauses) {
                for (Throwable cause : failureCauses) {
                    error.addSuppressed(cause);
                }
            }
            throw error;
        }
    }
}

