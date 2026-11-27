package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEvaluationJobStoreTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final MutableClock clock = new MutableClock();
    private final EvaluationJobStore store = new InMemoryEvaluationJobStore(clock);
    private final JobError error = new JobError("EXECUTION_INFRASTRUCTURE_ERROR", "Execution unavailable.", true);

    @Test
    void createsOneQueuedJobAndReplaysItWithoutChangingItsCreationTime() {
        var first = store.register(request("job-1"));
        clock.advance(Duration.ofSeconds(5));
        var replay = store.register(request("job-1"));
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.job()).isEqualTo(first.job());
        assertThat(replay.job().state()).isEqualTo(JobState.QUEUED);
        assertThat(replay.job().result()).isNull();
    }

    @Test
    void concurrentIdenticalRegistrationsCreateExactlyOneJob() throws Exception {
        int callers = 16;
        var ready = new CountDownLatch(callers);
        var release = new CountDownLatch(1);
        var futures = new ArrayList<Future<EvaluationJobStore.Registration>>();
        try (var pool = Executors.newFixedThreadPool(callers)) {
            try {
                for (int i = 0; i < callers; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        return store.register(request("same-job"));
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            var results = new ArrayList<EvaluationJobStore.Registration>();
            for (var future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(EvaluationJobStore.Registration::created).count()).isEqualTo(1);
            assertThat(results.stream().map(EvaluationJobStore.Registration::job).distinct().toList()).hasSize(1);
        }
    }

    @Test
    void detectsPayloadConflictsWithoutLeakingSourceOrOverwritingTheJob() {
        var first = store.register(request("same-job")).job();
        assertThatThrownBy(() -> store.register(changed("same-job")))
                .isInstanceOf(SubmissionIdConflictException.class)
                .hasMessage("Submission ID conflicts with an existing job");
        assertThat(store.find("same-job")).contains(first);
    }

    @Test
    void deduplicatesAndDetectsConflictsBeforeRejectingFullStorageWithoutEviction() {
        for (int i = 0; i < 100; i++) {
            store.register(request("job-" + i));
        }
        assertThat(store.register(request("job-0")).created()).isFalse();
        assertThatThrownBy(() -> store.register(changed("job-0"))).isInstanceOf(SubmissionIdConflictException.class);
        assertThatThrownBy(() -> store.register(request("overflow"))).isInstanceOf(JobStoreFullException.class);
        assertThat(store.find("job-0")).isPresent();
        assertThat(store.find("job-99")).isPresent();
        assertThat(store.find("overflow")).isEmpty();
    }

    @Test
    void followsQueuedRunningFinishedAndPreservesNegativeSolutionVerdicts() {
        var queued = store.register(request("job")).job();
        clock.advance(Duration.ofSeconds(1));
        var running = store.start("job");
        assertThat(running.createdAt()).isEqualTo(queued.createdAt());
        assertThat(running.state()).isEqualTo(JobState.RUNNING);
        assertThat(running.result()).isNull();
        clock.advance(Duration.ofSeconds(2));
        var finished = store.finish("job", result("job-wrong-answer.json"));
        assertThat(finished.state()).isEqualTo(JobState.FINISHED);
        assertThat(finished.result().status().name()).isEqualTo("WRONG_ANSWER");
        assertThat(finished.expiresAt()).isEqualTo(finished.finishedAt().plusSeconds(1800));
        assertThat(store.register(request("job")).job()).isEqualTo(finished);
    }

    @Test
    void permitsFailureBeforeOrAfterStartingButNeverRestartsTerminalJobs() {
        for (boolean start : List.of(false, true)) {
            String id = start ? "running" : "queued";
            store.register(request(id));
            if (start) {
                store.start(id);
            }
            var failed = store.fail(id, error);
            assertThat(failed.state()).isEqualTo(JobState.FAILED);
            assertThat(failed.startedAt() != null).isEqualTo(start);
            assertThat(failed.result()).isNull();
            assertThat(store.register(request(id)).job()).isEqualTo(failed);
            assertThatThrownBy(() -> store.start(id)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.fail(id, error)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.finish(id, result("job-finished.json")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsInvalidTransitionsWithoutChangingTheExistingSnapshot() {
        var queued = store.register(request("job")).job();
        assertThatThrownBy(() -> store.finish("job", result("job-finished.json")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find("job")).contains(queued);
        var running = store.start("job");
        assertThatThrownBy(() -> store.start("job")).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("job")).contains(running);
        var finished = store.finish("job", result("job-finished.json"));
        assertThatThrownBy(() -> store.fail("job", error)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("job")).contains(finished);
    }

    @Test
    void expiresAtTheTerminalDeadlineAndThenAllowsTheIdToBeRegisteredAgain() {
        store.register(request("job"));
        store.fail("job", error);
        clock.advance(Duration.ofSeconds(1799));
        assertThat(store.find("job")).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.find("job")).isEmpty();
        var recreated = store.register(changed("job"));
        assertThat(recreated.created()).isTrue();
        assertThat(recreated.job().state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void expiryFreesStorageButDoesNotRemoveOldNonterminalJobs() {
        for (int i = 0; i < 100; i++) {
            store.register(request("job-" + i));
        }
        store.start("job-0");
        store.finish("job-0", result("job-finished.json"));
        store.start("job-1");
        clock.advance(Duration.ofHours(1));
        assertThat(store.register(request("replacement")).created()).isTrue();
        assertThat(store.find("job-0")).isEmpty();
        assertThat(store.find("job-1")).isPresent();
        assertThat(store.find("job-2")).isPresent();
        assertThatThrownBy(() -> store.register(request("overflow"))).isInstanceOf(JobStoreFullException.class);
    }

    @Test
    void snapshotsDoNotExposeMutableDiagnosticNodesOwnedByTheStore() {
        store.register(request("job"));
        store.start("job");
        var supplied = result("job-finished.json");
        var returned = store.finish("job", supplied);
        supplied.testResults().getFirst().input().asObject().put("text", "modified input");
        returned.result().testResults().getFirst().input().asObject().put("text", "modified output");
        var read = store.find("job").orElseThrow();
        assertThat(read.result().testResults().getFirst().input().get("text").asString()).isEqualTo("codelobby");
        read.result().testResults().getFirst().input().asObject().put("text", "modified read");
        assertThat(store.find("job").orElseThrow().result().testResults().getFirst().input().get("text").asString())
                .isEqualTo("codelobby");
    }

    @Test
    void returnsEmptyForUnknownJobsAndDoesNotPersistAcrossStoreRecreation() {
        assertThat(store.find("unknown")).isEmpty();
        assertThatThrownBy(() -> store.start("unknown")).isInstanceOf(NoSuchElementException.class);
        store.register(request("job"));
        assertThat(new InMemoryEvaluationJobStore(clock).find("job")).isEmpty();
    }

    @Test
    void simultaneousConflictingRegistrationsHaveOneWinnerAndOneConflict() throws Exception {
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<String>>();
            try {
                for (var request : List.of(request("same-job"), changed("same-job"))) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        try {
                            return store.register(request).created() ? "created" : "replayed";
                        } catch (SubmissionIdConflictException exception) {
                            return "conflict";
                        }
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            var outcomes = new ArrayList<String>();
            for (var future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("created", "conflict");
        }
    }

    private SubmitRequest request(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits());
    }

    private SubmitRequest changed(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                List.of(new SubmissionFile("src/Solution.java", "private-source-marker")),
                original.testSuites(), original.limits());
    }

    private EvaluationResult result(String file) {
        return mapper.readValue(Path.of("docs/api/examples", file).toFile(), EvaluationJob.class).result();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-20T00:00:00Z"));

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
