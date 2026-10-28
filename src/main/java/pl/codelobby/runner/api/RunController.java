package pl.codelobby.runner.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@RestController
public final class RunController {

    static final int MAX_REQUEST_BYTES = 2000000;
    private final RunSubmissionUseCase useCase;
    private final JsonMapper requestMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    public RunController(RunSubmissionUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(path = "/internal/v1/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RunResponse run(HttpServletRequest request) throws IOException, HttpMediaTypeNotSupportedException {
        var mediaType = MediaType.parseMediaType(request.getContentType());
        String encoding = request.getHeader("Content-Encoding");
        if ((mediaType.getCharset() != null && !mediaType.getCharset().equals(StandardCharsets.UTF_8))
                || (encoding != null && !encoding.equalsIgnoreCase("identity"))) {
            throw new HttpMediaTypeNotSupportedException("Only uncompressed UTF-8 JSON is supported");
        }
        // Read at most one byte past the ceiling, including chunked requests without Content-Length.
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new RequestValidationException(ValidationErrorCode.PAYLOAD_TOO_LARGE, "Payload too large.");
        }
        RunRequest input;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            input = requestMapper.readValue(json, RunRequest.class);
        } catch (JacksonException | CharacterCodingException exception) {
            // Only request decoding is a client error; parser failures in an executor are server errors.
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid request.");
        }
        return useCase.execute(input);
    }
}
