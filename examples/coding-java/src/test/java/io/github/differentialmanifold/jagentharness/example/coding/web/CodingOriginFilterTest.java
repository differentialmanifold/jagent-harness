package io.github.differentialmanifold.jagentharness.example.coding.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;

class CodingOriginFilterTest {
    @Test
    void acceptsConfiguredLocalFrontendAndRejectsOtherOrigins() throws Exception {
        CodingOriginFilter filter = new CodingOriginFilter();
        ReflectionTestUtils.setField(filter, "consolePort", 5187);
        assertThat(status(filter, "http://127.0.0.1:5187")).isEqualTo(200);
        assertThat(status(filter, "http://localhost:5187")).isEqualTo(200);
        assertThat(status(filter, "https://untrusted.example")).isEqualTo(403);
        assertThat(status(filter, "http://127.0.0.1:5175")).isEqualTo(403);
        assertThat(status(filter, null)).isEqualTo(200);
    }

    private int status(CodingOriginFilter filter, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tools/config");
        request.setLocalPort(48180);
        if (origin != null) request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
