package io.github.differentialmanifold.jagentharness.example.coding.web;

import java.io.IOException;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local coding tools only accept the bundled UI origins; CLI clients have no Origin header. */
@Component
public class CodingOriginFilter extends OncePerRequestFilter {
    @Value("${coding.console-port:5175}")
    private int consolePort = 5175;

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String origin = req.getHeader("Origin");
        Set<String> allowed =
                new HashSet<>(
                        Arrays.asList(
                                "http://localhost:" + consolePort,
                                "http://127.0.0.1:" + consolePort,
                                "http://localhost:" + req.getLocalPort(),
                                "http://127.0.0.1:" + req.getLocalPort()));
        if (origin != null && !allowed.contains(origin)) {
            res.sendError(403, "Untrusted coding UI origin");
            return;
        }
        chain.doFilter(req, res);
    }
}
