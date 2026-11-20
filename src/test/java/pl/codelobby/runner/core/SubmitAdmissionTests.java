package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmitAdmissionTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final EvaluationJobStore store = new InMemoryEvaluationJobStore();

    @Test
    void fullRunCapacityRejectsSubmitWithoutPublishingAJobAndLaterAllowsRetry() {
        var queue = new ExecutionQueue(1, 0);
        var admission = new SubmitAdmission(store, queue);
        try (var occupied = queue.reserve(TIMEOUT)) {
            assertThatThrownBy(() -> admission.admit(request("job"))).isInstanceOf(RunQueueFullException.class);
            assertThat(store.find("job")).isEmpty();
        }
        var accepted = admission.admit(request("job"));
        assertThat(accepted.created()).isTrue();
        assertThat(accepted.job().state()).isEqualTo(JobState.QUEUED);
        accepted.reservation().close();
    }

    @Test
    void replayAndConflictAreResolvedBeforeCheckingFullExecutionCapacity() {
        var queue = new ExecutionQueue(1, 0);
        var admission = new SubmitAdmission(store, queue);
        var first = admission.admit(request("job"));
        try {
            var replay = admission.admit(request("job"));
            assertThat(replay.created()).isFalse();
            assertThat(replay.reservation()).isNull();
            assertThat(replay.job()).isEqualTo(first.job());
            assertThatThrownBy(() -> admission.admit(changed("job")))
                    .isInstanceOf(SubmissionIdConflictException.class);
            assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> "run"))
                    .isInstanceOf(RunQueueFullException.class);
        } finally {
            first.reservation().close();
        }
    }

    @Test
    void fullJobStorageDoesNotConsumeAnyExecutionCapacity() {
        for (int i = 0; i < 100; i++) {
            store.register(request("stored-" + i));
        }
        var queue = new ExecutionQueue(1, 0);
        assertThatThrownBy(() -> new SubmitAdmission(store, queue).admit(request("overflow")))
                .isInstanceOf(JobStoreFullException.class);
        var result = queue.execute(TIMEOUT, remaining -> "run");
        assertThat(result).isEqualTo("run");
        assertThat(store.find("overflow")).isEmpty();
    }

    @Test
    void concurrentReplaysOwnExactlyOneReservation() throws Exception {
        int callers = 8;
        var queue = new ExecutionQueue(1, 0);
        var admission = new SubmitAdmission(store, queue);
        var ready = new CountDownLatch(callers);
        var release = new CountDownLatch(1);
        var results = new ArrayList<SubmitAdmission.AdmittedJob>();
        try (var threads = Executors.newFixedThreadPool(callers)) {
            var futures = new ArrayList<Future<SubmitAdmission.AdmittedJob>>();
            try {
                for (int i = 0; i < callers; i++) {
                    futures.add(threads.submit(() -> {
                        ready.countDown();
                        await(release);
                        return admission.admit(request("same-job"));
                    }));
                }
                await(ready);
            } finally {
                release.countDown();
            }
            for (var future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
        }
        try {
            assertThat(results.stream().filter(SubmitAdmission.AdmittedJob::created).count()).isEqualTo(1);
            assertThat(results.stream().filter(value -> value.reservation() != null).count()).isEqualTo(1);
        } finally {
            results.stream().filter(value -> value.reservation() != null).forEach(value -> value.reservation().close());
        }
        var result = queue.execute(TIMEOUT, remaining -> "available");
        assertThat(result).isEqualTo("available");
    }

    @Test
    void concurrentDistinctSubmissionsStayWithinSharedCapacityWithoutGhostJobs() throws Exception {
        int callers = 8;
        var queue = new ExecutionQueue(2, 1);
        var admission = new SubmitAdmission(store, queue);
        var ready = new CountDownLatch(callers);
        var release = new CountDownLatch(1);
        var accepted = new ArrayList<SubmitAdmission.AdmittedJob>();
        try (var threads = Executors.newFixedThreadPool(callers)) {
            var futures = new ArrayList<Future<Optional<SubmitAdmission.AdmittedJob>>>();
            try {
                for (int i = 0; i < callers; i++) {
                    var input = request("job-" + i);
                    futures.add(threads.submit(() -> {
                        ready.countDown();
                        await(release);
                        try {
                            return Optional.of(admission.admit(input));
                        } catch (RunQueueFullException exception) {
                            return Optional.empty();
                        }
                    }));
                }
                await(ready);
            } finally {
                release.countDown();
            }
            for (int i = 0; i < callers; i++) {
                var result = futures.get(i).get(5, TimeUnit.SECONDS);
                assertThat(store.find("job-" + i).isPresent()).isEqualTo(result.isPresent());
                result.ifPresent(accepted::add);
            }
        }
        try {
            assertThat(accepted).hasSize(3);
        } finally {
            accepted.forEach(value -> value.reservation().close());
        }
    }

    @Test
    void reservationsPreserveFifoEvenWhenDispatchOccursInReverseOrder() throws Exception {
        var queue = new ExecutionQueue(1, 1);
        var admission = new SubmitAdmission(store, queue);
        var first = admission.admit(request("first"));
        var second = admission.admit(request("second"));
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        var waiting = new FutureTask<>(() -> second.reservation().execute(remaining -> {
            order.add("second");
            return true;
        }));
        var thread = new Thread(waiting);
        thread.start();
        try {
            awaitState(thread, Thread.State.TIMED_WAITING);
            first.reservation().execute(remaining -> {
                order.add("first");
                return true;
            });
            assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("first", "second");
        } finally {
            first.reservation().close();
            second.reservation().close();
        }
    }

    @Test
    void deadlineIncludesTimeBetweenAdmissionAndDispatchAndDoesNotQuarantineUnusedWork() {
        var time = new AtomicLong();
        var queue = new ExecutionQueue(1, 0, time::get);
        var accepted = new SubmitAdmission(store, queue).admit(request("job"));
        var invoked = new AtomicBoolean();
        time.set(Duration.ofSeconds(180).toNanos());
        assertThatThrownBy(() -> accepted.reservation().execute(remaining -> {
            invoked.set(true);
            return "expired";
        })).isInstanceOf(RunDeadlineExceededException.class);
        assertThat(invoked).isFalse();
        var result = queue.execute(TIMEOUT, remaining -> "run");
        assertThat(result).isEqualTo("run");
    }

    @Test
    void closingARunningReservationDoesNotReleaseItsCapacity() throws Exception {
        var queue = new ExecutionQueue(1, 0);
        var admission = queue.reserve(TIMEOUT);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = new FutureTask<>(() -> admission.execute(remaining -> {
            entered.countDown();
            await(release);
            return true;
        }));
        new Thread(running).start();
        try {
            await(entered);
            admission.close();
            assertThatThrownBy(() -> queue.reserve(TIMEOUT)).isInstanceOf(RunQueueFullException.class);
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, TimeUnit.SECONDS)).isTrue();
        var result = queue.execute(TIMEOUT, remaining -> "next");
        assertThat(result).isEqualTo("next");
    }

    @Test
    void closingAnUnusedReservationIsIdempotentAndCannotInflateCapacity() {
        var queue = new ExecutionQueue(1, 0);
        var cancelled = queue.reserve(TIMEOUT);
        cancelled.close();
        cancelled.close();
        assertThatThrownBy(() -> cancelled.execute(remaining -> "closed"))
                .isInstanceOf(IllegalStateException.class);
        try (var next = queue.reserve(TIMEOUT)) {
            assertThatThrownBy(() -> queue.reserve(TIMEOUT)).isInstanceOf(RunQueueFullException.class);
        }
    }

    @Test
    void failedStorePublicationReleasesTheReservation() {
        var brokenStore = mock(EvaluationJobStore.class);
        when(brokenStore.register(any(SubmitRequest.class), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            throw new IllegalStateException("Publication rejected");
        });
        var queue = new ExecutionQueue(1, 0);
        assertThatThrownBy(() -> new SubmitAdmission(brokenStore, queue).admit(request("job")))
                .isInstanceOf(IllegalStateException.class);
        var result = queue.execute(TIMEOUT, remaining -> "available");
        assertThat(result).isEqualTo("available");
    }

    @Test
    void quarantineRejectsNewJobsWithoutPublishingThemButAllowsStoredReplays() {
        var queue = new ExecutionQueue(1, 0);
        var admission = new SubmitAdmission(store, queue);
        var first = admission.admit(request("existing"));
        first.reservation().execute(remaining -> {
            store.start("existing");
            var result = mapper.readValue(Path.of("docs/api/examples/job-finished.json").toFile(), EvaluationJob.class)
                    .result();
            return store.finish("existing", result);
        });
        assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> {
            throw new IllegalStateException("Termination unconfirmed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(admission.admit(request("existing")).job().state()).isEqualTo(JobState.FINISHED);
        assertThatThrownBy(() -> admission.admit(request("new-job")))
                .isInstanceOf(ExecutionUnavailableException.class);
        assertThat(store.find("new-job")).isEmpty();
    }

    @Test
    void rejectedPublicationIsNeverVisibleToConcurrentReaders() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registering = new FutureTask<>(() -> store.register(request("job"), () -> {
            entered.countDown();
            await(release);
            throw new RunQueueFullException();
        }));
        var reading = new FutureTask<>(() -> store.find("job"));
        new Thread(registering).start();
        var reader = new Thread(reading);
        try {
            await(entered);
            reader.start();
            awaitState(reader, Thread.State.BLOCKED);
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> registering.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RunQueueFullException.class);
        assertThat(reading.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void cancellingWaitingWorkNeverInvokesItAndAllowsAnotherReservation() throws Exception {
        var queue = new ExecutionQueue(1, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invoked = new AtomicBoolean();
        var active = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            return true;
        }));
        new Thread(active).start();
        ExecutionQueue.Admission cancelled = null;
        try {
            await(entered);
            cancelled = queue.reserve(TIMEOUT);
            var reserved = cancelled;
            var waiting = new FutureTask<>(() -> reserved.execute(remaining -> {
                invoked.set(true);
                return true;
            }));
            var thread = new Thread(waiting);
            thread.start();
            awaitState(thread, Thread.State.TIMED_WAITING);
            cancelled.close();
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionUnavailableException.class);
            try (var replacement = queue.reserve(TIMEOUT)) {
                assertThat(invoked).isFalse();
            }
        } finally {
            if (cancelled != null) {
                cancelled.close();
            }
            release.countDown();
        }
        assertThat(active.get(5, TimeUnit.SECONDS)).isTrue();
        var next = queue.execute(TIMEOUT, remaining -> "available");
        assertThat(next).isEqualTo("available");
    }

    @Test
    void aCompletedReservationCannotExecuteOrReleaseCapacityAgain() {
        var queue = new ExecutionQueue(1, 0);
        var reservation = queue.reserve(TIMEOUT);
        var result = reservation.execute(remaining -> "first");
        assertThat(result).isEqualTo("first");
        assertThatThrownBy(() -> reservation.execute(remaining -> "second"))
                .isInstanceOf(IllegalStateException.class);
        reservation.close();
        try (var next = queue.reserve(TIMEOUT)) {
            assertThatThrownBy(() -> queue.reserve(TIMEOUT)).isInstanceOf(RunQueueFullException.class);
        }
    }

    private SubmitRequest request(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits());
    }

    private SubmitRequest changed(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                List.of(new SubmissionFile("src/Solution.java", "changed source")),
                original.testSuites(), original.limits());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).isEqualTo(expected);
    }
}
