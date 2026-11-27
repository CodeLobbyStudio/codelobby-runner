package pl.codelobby.runner.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import pl.codelobby.runner.core.SubmitJobUseCase;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmitRequest;

@RestController
public final class SubmitController {
    private final SubmitJobUseCase useCase;
    private final InternalJsonRequestReader reader = new InternalJsonRequestReader();

    public SubmitController(SubmitJobUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(path = "/internal/v1/evaluation-jobs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvaluationJob> submit(HttpServletRequest request) throws IOException, HttpMediaTypeNotSupportedException {
        var accepted = useCase.submit(reader.read(request, SubmitRequest.class));
        var job = accepted.job();
        boolean terminal = job.state() == JobState.FINISHED || job.state() == JobState.FAILED;
        var location = UriComponentsBuilder.fromPath("/internal/v1/evaluation-jobs/{id}")
                .buildAndExpand(job.submissionId()).encode().toUri();
        return ResponseEntity.status(!accepted.created() && terminal ? 200 : 202)
                .location(location).header("Cache-Control", "no-store").body(job);
    }

    @GetMapping(path = "/internal/v1/evaluation-jobs/{submissionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvaluationJob> find(@PathVariable String submissionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(useCase.find(submissionId));
    }
}
