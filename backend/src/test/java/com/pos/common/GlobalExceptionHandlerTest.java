package com.pos.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;

/** Unexpected errors never show their message (it can carry SQL or data) to the client. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorsShowOnlyAReference() throws Exception {
        var res = handler.handleUnexpected(new IllegalStateException("SELECT password_hash FROM users WHERE id = 1"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        ProblemDetail pd = (ProblemDetail) res.getBody();
        assertThat(pd.getDetail()).startsWith("Something went wrong").doesNotContain("SELECT", "password");
        assertThat(pd.getProperties()).containsKey("reference");
        assertThat(pd.getDetail()).contains((String) pd.getProperties().get("reference"));
    }

    @Test
    void signInProblemsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> handler.handleUnexpected(new BadCredentialsException("x"))).isInstanceOf(BadCredentialsException.class);
    }
}
