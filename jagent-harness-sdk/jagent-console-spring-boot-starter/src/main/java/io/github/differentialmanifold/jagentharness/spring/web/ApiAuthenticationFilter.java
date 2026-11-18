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
    private final UserAccessService access;

    public ApiAuthenticationFilter(UserAccessService access) {
        this.token = "";
        this.access = access;
    }

    public ApiAuthenticationFilter(
            @Value("${harness.protocol.token:}") String token,
            @Value("${server.address:0.0.0.0}") String bind) {
        this.token = token;
        this.access = null;
        if (token.isEmpty()
                && !("127.0.0.1".equals(bind) || "::1".equals(bind) || "localhost".equals(bind)))
            throw new IllegalArgumentException(
                    "harness.protocol.token is required for a non-loopback server binding");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (access != null && req.getRequestURI().startsWith("/api/v1/")) {
            String path = req.getRequestURI();
            res.setHeader("Cache-Control", "no-store");
            if (path.equals("/api/v1/access/status")
                    || path.equals("/api/v1/access/pair/start")
                    || path.equals("/api/v1/access/pair/poll")
                    || path.equals("/api/v1/access/exchange")
                    || path.equals("/api/v1/health")) {
                if ((path.endsWith("/exchange") || path.contains("/pair/")) && !sameOrigin(req)) {
                    reject(res, 403, "CSRF", "Cross-origin access is not allowed");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            String bearer = UserAccessService.bearer(req);
            io.github.differentialmanifold.jagentharness.core.identity.UserStore.User user =
                    access.users.authenticate(bearer);
            io.github.differentialmanifold.jagentharness.core.identity.UserStore.Access browser =
                    null;
            if (user == null && bearer == null) {
                browser = access.browser(req);
                if (browser != null && access.valid(browser))
                    user = access.users.get(browser.userId);
            }
            if (user == null) {
                reject(
                        res,
                        401,
                        "UNAUTHORIZED",
                        "Start your local Runner with a valid user token and open the console from it");
                return;
            }
            if (browser != null
                    && !java.util.Arrays.asList("GET", "HEAD", "OPTIONS").contains(req.getMethod())
                    && !sameOrigin(req)) {
                reject(res, 403, "CSRF", "Cross-origin access is not allowed");
                return;
            }
            boolean accessManagement = path.startsWith("/api/v1/access/");
            if (browser != null && !accessManagement && !access.workspaceReady(user.userId)) {
                reject(
                        res,
                        503,
                        "MAINBOT_OFFLINE",
                        "Waiting for mainbot: start its Runner to access your workspace");
                return;
            }
            final io.github.differentialmanifold.jagentharness.core.identity.UserStore.Access
                    session = browser;
            final String acceptedToken = bearer;
            java.util.function.BooleanSupplier valid =
                    () ->
                            session == null
                                    ? access.users.authenticate(acceptedToken) != null
                                    : access.valid(session)
                                            && (accessManagement
                                                    || access.workspaceReady(session.userId));
            req.setAttribute(UserAccessService.ATTRIBUTE, valid);
            try (io.github.differentialmanifold.jagentharness.core.identity.UserContext ignored =
                    io.github.differentialmanifold.jagentharness.core.identity.UserContext.enter(
                            user.userId)) {
                // Access management must be able to revoke the current session and return a
                // response.
                chain.doFilter(req, accessManagement ? res : new GuardedResponse(res, valid));
            }
            return;
        }
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

    private static boolean sameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        String site = req.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(site)) return false;
        if (origin == null) return true;
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String expected =
                    req.getScheme()
                            + "://"
                            + req.getServerName()
                            + ((req.getServerPort() == 80 && "http".equals(req.getScheme()))
                                            || (req.getServerPort() == 443
                                                    && "https".equals(req.getScheme()))
                                    ? ""
                                    : ":" + req.getServerPort());
            return expected.equalsIgnoreCase(uri.toString());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse res, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static final class GuardedResponse extends HttpServletResponseWrapper {
        private final java.util.function.BooleanSupplier valid;

        GuardedResponse(HttpServletResponse response, java.util.function.BooleanSupplier valid) {
            super(response);
            this.valid = valid;
        }

        @Override
        public javax.servlet.ServletOutputStream getOutputStream() throws IOException {
            javax.servlet.ServletOutputStream out = super.getOutputStream();
            return new javax.servlet.ServletOutputStream() {
                public boolean isReady() {
                    return out.isReady();
                }

                public void setWriteListener(javax.servlet.WriteListener listener) {
                    out.setWriteListener(listener);
                }

                public void write(int b) throws IOException {
                    check();
                    out.write(b);
                }

                public void write(byte[] b, int off, int len) throws IOException {
                    check();
                    out.write(b, off, len);
                }

                public void flush() throws IOException {
                    check();
                    out.flush();
                }

                private void check() throws IOException {
                    if (!valid.getAsBoolean())
                        throw new IOException("Browser access expired or Runner disconnected");
                }
            };
        }
    }
}
