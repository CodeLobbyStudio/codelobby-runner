package pl.codelobby.runner.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;

@RestController
public final class RunController {
    static final int MAX_REQUEST_BYTES = InternalJsonRequestReader.MAX_REQUEST_BYTES;
    private final RunSubmissionUseCase useCase;
    private final InternalJsonRequestReader reader = new InternalJsonRequestReader();

    public RunController(RunSubmissionUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(path = "/internal/v1/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RunResponse run(HttpServletRequest request) throws IOException, HttpMediaTypeNotSupportedException {
        return useCase.execute(reader.read(request, RunRequest.class));
    }
}
