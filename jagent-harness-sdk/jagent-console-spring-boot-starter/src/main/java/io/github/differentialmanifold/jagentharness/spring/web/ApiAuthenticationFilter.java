package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final String token;

    public ApiAuthenticationFilter(
            @Value("${agent.protocol.token:}") String token,
            @Value("${server.address:0.0.0.0}") String bind) {
        this.token = token;
        if (token.isEmpty()
                && !("127.0.0.1".equals(bind) || "::1".equals(bind) || "localhost".equals(bind)))
            throw new IllegalArgumentException(
                    "agent.protocol.token is required for a non-loopback server binding");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!token.isEmpty() && req.getRequestURI().startsWith("/api/v1/")) {
            String actual = req.getHeader("Authorization");
            if (actual == null
                    || !MessageDigest.isEqual(
                            ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                            actual.getBytes(StandardCharsets.UTF_8))) {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter()
                        .write(
                                "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid client token is required\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
