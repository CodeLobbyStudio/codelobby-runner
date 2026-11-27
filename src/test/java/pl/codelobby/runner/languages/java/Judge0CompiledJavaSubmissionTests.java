package pl.codelobby.runner.languages.java;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SubmissionFingerprint;
import pl.codelobby.runner.core.SuiteEvaluation;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.BenchmarkReason;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactException;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactSnapshot;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0Result;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteReport;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Judge0CompiledJavaSubmissionTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest request = mapper.readValue(Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final JavaSubmitSuiteExecutor initial = mock(JavaSubmitSuiteExecutor.class);
    private final Judge0Client client = mock(Judge0Client.class);
    private final Judge0ArtifactStore store = mock(Judge0ArtifactStore.class);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger posts = new AtomicInteger();

    private Judge0CompiledJavaSubmission open() throws Exception {
        for (int index = 0; index < 2; index++) {
            var suite = request.testSuites().get(index);
            var cases = suite.cases().stream().map(test -> new TestResult(test.testId(), suite.suiteId(), suite.visibility(),
                    TestVerdict.ACCEPTED, null, null, null, null, null)).toList();
            when(initial.execute(any(), eq(index), any(), anyInt(), anyInt(), any())).thenReturn(
                    new JavaSubmitSuiteExecutor.Outcome(new SuiteEvaluation(Verdict.ACCEPTED, cases),
                            new Judge0CompilationUsage(10, 20), 10.0, 20.0, 32000000L, token(index)));
            String name = index == 0 ? "PublicTests.class" : "HiddenTests.class";
            byte[] raw = mapper.writeValueAsBytes(Map.of("version", 1, "files", List.of(
                    Map.of("path", name, "contentBase64", "yv66vg=="))));
            when(store.fetch(eq(token(index)), any())).thenReturn(new Judge0ArtifactSnapshot(raw,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))));
        }
        configureReplay();
        return create();
    }

    private Judge0CompiledJavaSubmission create() {
        return Judge0CompiledJavaSubmission.open(request, initial, client, store,
                new Judge0SuiteEvaluator(new SuiteReportParser()), Duration.ofSeconds(60), clock::get);
    }

    private void configureReplay() {
        when(client.submitProject(any(), any(), any())).thenAnswer(call -> token(10 + posts.getAndIncrement()));
        when(client.awaitResult(any(), any())).thenAnswer(call -> new Judge0Result(call.getArgument(0),
                Judge0Status.ACCEPTED, null, null, null, null, 5.0, 10.0, 32000000L, 0, null));
        when(client.getSuiteReport(any(), any())).thenAnswer(call -> {
            String id = posts.get() % 2 == 1 ? "public-1" : "hidden-1";
            return new Judge0SuiteReport(Judge0SuiteReport.State.AVAILABLE,
                    ("{\"testId\":\"" + id + "\",\"status\":\"ACCEPTED\"}\n").getBytes(StandardCharsets.UTF_8),
                    new Judge0CompilationUsage(0, 0));
        });
    }

    @Test
    void evaluatesOnceAndRunsAllSevenRoundsWithoutRecompiling() throws Exception {
        try (var session = open()) {
            assertThat(session.submissionId()).isEqualTo(request.submissionId());
            assertThat(session.payloadFingerprint()).isEqualTo(SubmissionFingerprint.compute(request));
            assertThat(session.evaluation().passedTests()).isEqualTo(2);
            var benchmark = new JavaSubmitBenchmarkRunner(clock::get).run(request, session, Duration.ofSeconds(60));
            assertThat(benchmark.state()).isEqualTo(BenchmarkState.SUCCEEDED);
            assertThat(benchmark.metrics().roundRuntimeMs()).containsExactly(10.0, 10.0, 10.0, 10.0, 10.0);
            assertThat(posts.get()).isEqualTo(14);
            verify(initial, times(2)).execute(any(), anyInt(), any(), anyInt(), anyInt(), any());
            verify(store).delete(eq(token(0)), any());
            verify(store).delete(eq(token(1)), any());
            assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closePreventsAnotherLaunch() throws Exception {
        var session = open();
        session.close();
        assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test
    void missingArtifactsFailBenchmarkWithoutChangingAcceptedEvaluation() throws Exception {
        var first = open();
        first.close();
        when(store.fetch(any(), any())).thenThrow(new Judge0ArtifactException(Judge0ArtifactException.Reason.MISSING));
        try (var session = create()) {
            var benchmark = new JavaSubmitBenchmarkRunner(clock::get).run(request, session, Duration.ofSeconds(60));
            assertThat(session.evaluation().status()).isEqualTo(Verdict.ACCEPTED);
            assertThat(benchmark.state()).isEqualTo(BenchmarkState.FAILED);
            assertThat(benchmark.reason()).isEqualTo(BenchmarkReason.MEASUREMENT_UNAVAILABLE);
            assertThat(benchmark.metrics()).isNull();
            verifyNoInteractions(client);
        }
    }

    @Test
    void rejectsCorruptDigestWithoutRunningArtifacts() throws Exception {
        open().close();
        when(store.fetch(any(), any())).thenReturn(new Judge0ArtifactSnapshot("{}".getBytes(StandardCharsets.UTF_8), "a".repeat(64)));
        try (var session = create()) {
            assertThat(new JavaSubmitBenchmarkRunner(clock::get).run(request, session, Duration.ofSeconds(60)).state())
                    .isEqualTo(BenchmarkState.FAILED);
            verifyNoInteractions(client);
        }
    }

    @Test
    void initialEvaluationAndRoundsShareTheSameDeadline() throws Exception {
        try (var session = open()) {
            clock.set(Duration.ofSeconds(61).toNanos());
            assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(60)))
                    .isInstanceOf(RunDeadlineExceededException.class);
            verifyNoInteractions(client);
        }
    }

    @Test
    void unsafeTransportFailurePreventsRetryAndRetainsTheExecutionToken() throws Exception {
        try (var session = open()) {
            var failure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token(10));
            doThrow(failure).when(client).awaitResult(any(), any());
            assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(30))).isSameAs(failure);
            assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(posts.get()).isEqualTo(1);
        }
    }

    @Test
    void doesNotAllowChangingExecutionLimitsForRounds() throws Exception {
        try (var session = open()) {
            assertThatThrownBy(() -> session.executeRound(new ExecutionLimits(1, 1, 16000000), Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(client);
        }
    }

    @Test
    void artifactInterruptionIsNotConvertedIntoAnOrdinaryBenchmarkFailure() throws Exception {
        open().close();
        when(store.fetch(any(), any())).thenThrow(new Judge0ArtifactException(Judge0ArtifactException.Reason.INTERRUPTED));
        try {
            assertThatThrownBy(this::create).isInstanceOf(pl.codelobby.runner.core.ExecutionUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(client);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unsuccessfulEvaluationSkipsAllBenchmarkExecutionAndArtifactRetrieval() throws Exception {
        open().close();
        clearInvocations(initial, store, client);
        var suite = request.testSuites().getFirst();
        var cases = List.of(new TestResult("public-1", suite.suiteId(), suite.visibility(), TestVerdict.WRONG_ANSWER,
                null, null, null, null, null));
        when(initial.execute(any(), eq(0), any(), anyInt(), anyInt(), any())).thenReturn(
                new JavaSubmitSuiteExecutor.Outcome(new SuiteEvaluation(Verdict.WRONG_ANSWER, cases),
                        new Judge0CompilationUsage(10, 20), 10.0, 20.0, 32000000L, token(0)));
        try (var session = create()) {
            assertThat(session.evaluation().status()).isEqualTo(Verdict.WRONG_ANSWER);
            assertThat(new JavaSubmitBenchmarkRunner(clock::get).run(request, session, Duration.ofSeconds(30)).state())
                    .isEqualTo(BenchmarkState.SKIPPED);
            verify(store, never()).fetch(any(), any());
            verifyNoInteractions(client);
        }
    }

    @Test
    void sessionOwnsItsRequestSnapshotAfterCallerMutatesTheInputLists() throws Exception {
        open().close();
        var files = new java.util.ArrayList<>(request.taskFiles());
        var suites = new java.util.ArrayList<>(request.testSuites());
        var input = new SubmitRequest(request.submissionId(), request.taskVersionId(), request.language(), files,
                request.submissionFiles(), suites, request.limits());
        try (var session = Judge0CompiledJavaSubmission.open(input, initial, client, store,
                new Judge0SuiteEvaluator(new SuiteReportParser()), Duration.ofSeconds(60), clock::get)) {
            String fingerprint = session.payloadFingerprint();
            files.clear();
            suites.clear();
            assertThat(session.executeRound(request.limits(), Duration.ofSeconds(30)).status()).isEqualTo(Verdict.ACCEPTED);
            assertThat(session.payloadFingerprint()).isEqualTo(fingerprint);
        }
    }

    @Test
    void deletionInterruptionIsNotSilentlyIgnored() throws Exception {
        open().close();
        doThrow(new Judge0ArtifactException(Judge0ArtifactException.Reason.INTERRUPTED)).when(store).delete(any(), any());
        try {
            assertThatThrownBy(this::create).isInstanceOf(pl.codelobby.runner.core.ExecutionUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unavailableArtifactsDoNotBypassTheOriginalDeadline() throws Exception {
        open().close();
        when(store.fetch(any(), any())).thenThrow(new Judge0ArtifactException(Judge0ArtifactException.Reason.MISSING));
        try (var session = create()) {
            clock.set(Duration.ofSeconds(61).toNanos());
            assertThatThrownBy(() -> session.executeRound(request.limits(), Duration.ofSeconds(60)))
                    .isInstanceOf(RunDeadlineExceededException.class);
            verifyNoInteractions(client);
        }
    }

    @Test
    void missingCleanupConfirmationPreventsArtifactUseAndReturningEvaluation() throws Exception {
        open().close();
        clearInvocations(store, client);
        doThrow(new Judge0ArtifactException(Judge0ArtifactException.Reason.UNAVAILABLE)).when(store).awaitCleanup(any(), any());
        assertThatThrownBy(this::create).isInstanceOf(pl.codelobby.runner.core.ExecutionUnavailableException.class);
        verify(store, never()).fetch(any(), any());
        verifyNoInteractions(client);
    }

    private Judge0SubmissionToken token(int index) {
        return new Judge0SubmissionToken("aaaaaaaa-bbbb-cccc-dddd-" + String.format("%012x", index));
    }
}
