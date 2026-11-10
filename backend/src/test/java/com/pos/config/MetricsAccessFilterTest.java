package com.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetricsAccessFilterTest {

    private static int status(String token, String path, String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api" + path);
        req.setContextPath("/api");
        req.setServletPath(path);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        new MetricsAccessFilter(token).doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void withoutATokenTheMetricsAreOff() throws Exception {
        assertThat(status(null, "/actuator/prometheus", "Bearer anything")).isEqualTo(404);
        assertThat(status(" ", "/actuator/prometheus", null)).isEqualTo(404);
    }

    @Test
    void onlyTheTokenGetsThrough() throws Exception {
        assertThat(status("s3cret", "/actuator/prometheus", "Bearer s3cret")).isEqualTo(200);
        assertThat(status("s3cret", "/actuator/prometheus", "Bearer s3cre")).isEqualTo(401);
        assertThat(status("s3cret", "/actuator/prometheus", "s3cret")).isEqualTo(401);
        assertThat(status("s3cret", "/actuator/prometheus", null)).isEqualTo(401);
    }

    @Test
    void otherPathsAreLeftAlone() throws Exception {
        assertThat(status(null, "/actuator/health", null)).isEqualTo(200);
        assertThat(status("s3cret", "/orders", null)).isEqualTo(200);
    }
}
