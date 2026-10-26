package pl.codelobby.runner.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiFilterTests {

    @Test
    void missingConfigurationNeverAcceptsAnEmptyOrArbitraryKey() throws Exception {
        var filter = new InternalApiFilter("");
        for (String key : new String[] {"", "synthetic-test-key-not-a-secret-123456"}) {
            var request = new MockHttpServletRequest("POST", "/internal/v1/runs");
            request.setServletPath("/internal/v1/runs");
            request.addHeader("X-CodeLobby-Internal-Key", key);
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, (input, output) -> {
                throw new AssertionError("Unconfigured authentication must reject all internal requests");
            });

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        }
    }

    @Test
    void rejectsAShortConfiguredKeyWithoutIncludingItInTheMessage() {
        assertThatThrownBy(() -> new InternalApiFilter("short-secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("short-secret");
    }
}
