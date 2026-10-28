package pl.codelobby.runner.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Authenticates internal requests without logging their keys or bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class InternalApiFilter extends OncePerRequestFilter {

    private static final String KEY_HEADER = "X-CodeLobby-Internal-Key";
    private final byte[] expectedDigest;
    private final boolean configured;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public InternalApiFilter(@Value("${runner.internal-key:}") String key) {
        configured = !key.isEmpty();
        if (configured && key.codePointCount(0, key.length()) < 32) {
            throw new IllegalArgumentException("The internal key must contain at least 32 characters");
        }
        expectedDigest = digest(key);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/internal") && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > RunController.MAX_REQUEST_BYTES) {
            reject(response, 413, new ApiError("PAYLOAD_TOO_LARGE", "Payload too large.", false));
            return;
        }
        var keys = Collections.list(request.getHeaders(KEY_HEADER));
        String supplied = keys.size() == 1 ? keys.getFirst() : "";
        boolean matches = MessageDigest.isEqual(expectedDigest, digest(supplied));
        if (!configured || keys.size() != 1 || !matches) {
            reject(response, 401, new ApiError("UNAUTHORIZED", "Unauthorized.", false));
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, ApiError error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), error);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
