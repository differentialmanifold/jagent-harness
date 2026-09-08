package io.github.differentialmanifold.jagentharness.example.coding.web;

import java.io.IOException;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local coding tools only accept the bundled UI origins; CLI clients have no Origin header. */
@Component
public class CodingOriginFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String origin = req.getHeader("Origin");
        Set<String> allowed =
                new HashSet<>(
                        Arrays.asList(
                                "http://localhost:5175",
                                "http://127.0.0.1:5175",
                                "http://localhost:" + req.getLocalPort(),
                                "http://127.0.0.1:" + req.getLocalPort()));
        if (origin != null && !allowed.contains(origin)) {
            res.sendError(403, "Untrusted coding UI origin");
            return;
        }
        chain.doFilter(req, res);
    }
}
