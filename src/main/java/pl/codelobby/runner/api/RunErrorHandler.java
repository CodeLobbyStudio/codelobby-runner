package pl.codelobby.runner.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.RunQueueFullException;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;

/** Never echoes exception messages, request content, source paths or internal traces. */
@RestControllerAdvice
public class RunErrorHandler {

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> validation(RequestValidationException exception) {
        return switch (exception.code()) {
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "Invalid request.", false);
            case INVALID_SOURCE_FILES -> error(400, "INVALID_SOURCE_FILES", "Invalid source files.", false);
            case PAYLOAD_TOO_LARGE -> error(413, "PAYLOAD_TOO_LARGE", "Payload too large.", false);
        };
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMedia() {
        return error(415, "INVALID_REQUEST", "Unsupported request media type.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMethod() {
        return error(405, "INVALID_REQUEST", "Unsupported request method.", false);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> unacceptableMedia() {
        return error(406, "INVALID_REQUEST", "Unsupported response media type.", false);
    }

    @ExceptionHandler(ExecutionUnavailableException.class)
    ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(503).header("Cache-Control", "no-store").header("Retry-After", "1")
                .body(new ApiError("EXECUTION_UNAVAILABLE", "Execution unavailable.", true));
    }

    @ExceptionHandler(Judge0ClientException.class)
    ResponseEntity<ApiError> engineFailure(Judge0ClientException exception) {
        return exception.reason() == Judge0ClientException.Reason.TIMEOUT ? deadline() : unavailable();
    }

    @ExceptionHandler(RunDeadlineExceededException.class)
    ResponseEntity<ApiError> deadline() {
        return error(504, "REQUEST_DEADLINE_EXCEEDED", "Request deadline exceeded.", true);
    }

    @ExceptionHandler(RunQueueFullException.class)
    ResponseEntity<ApiError> fullQueue() {
        return ResponseEntity.status(429).header("Cache-Control", "no-store").header("Retry-After", "1")
                .body(new ApiError("QUEUE_CAPACITY_EXCEEDED", "Queue capacity exceeded.", true));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> unknownRoute() {
        return error(404, "INVALID_REQUEST", "Unknown endpoint.", false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedFailure() {
        return error(500, "INTERNAL_SERVER_ERROR", "Internal server error.", false);
    }

    private ResponseEntity<ApiError> error(int status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new ApiError(code, message, retryable));
    }
}
