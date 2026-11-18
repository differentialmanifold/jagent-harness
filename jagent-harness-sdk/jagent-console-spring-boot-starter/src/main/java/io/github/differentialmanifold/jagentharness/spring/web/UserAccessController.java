package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import java.util.*;
import javax.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/access")
public final class UserAccessController {
    private final UserAccessService service;

    public UserAccessController(UserAccessService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req, HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        UserStore.Access a = service.browser(req);
        Map<String, Object> result = new LinkedHashMap<>();
        boolean authenticated = a != null && service.valid(a);
        result.put("authenticated", authenticated);
        result.put("mainbotOnline", authenticated && service.workspaceReady(a.userId));
        result.put("insecure", !req.isSecure());
        if (authenticated) {
            result.put("user", service.users.get(a.userId));
        }
        return result;
    }

    @GetMapping("/devices")
    public List<UserStore.Device> devices(HttpServletRequest req) {
        UserStore.Access a = service.requireBrowser(req);
        List<UserStore.Device> devices = service.users.devices(a.userId);
        for (UserStore.Device device : devices)
            device.current = device.id.equals(Credentials.hash(a.secret));
        return devices;
    }

    @PostMapping("/devices/link")
    public Map<String, Object> deviceLink(HttpServletRequest req) {
        UserStore.Access browser = service.requireBrowser(req);
        UserStore.Access ticket =
                service.users.createTicket(browser.userId, browser.credentialHash);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", ticket.secret);
        result.put("expiresAt", ticket.expiresAt);
        return result;
    }

    @DeleteMapping("/devices/{id}")
    public Map<String, Boolean> revokeDevice(
            @PathVariable String id, HttpServletRequest req, HttpServletResponse res) {
        UserStore.Access a = service.requireBrowser(req);
        service.users.revokeDevice(a.userId, id);
        if (id.equals(Credentials.hash(a.secret))) UserAccessService.cookie(req, res, "", 0);
        return Collections.singletonMap("revoked", true);
    }

    @GetMapping("/identity")
    public UserStore.User identity(HttpServletRequest req) {
        UserStore.User user = service.users.authenticate(UserAccessService.bearer(req));
        if (user == null) throw UserAccessService.unauthorized();
        return user;
    }

    @PostMapping("/browser")
    public Map<String, String> browser(
            HttpServletRequest req,
            @RequestHeader("X-Runner-Id") String runner,
            @RequestHeader("X-Runner-Secret") String secret) {
        return Collections.singletonMap(
                "ticket",
                service.browserTicket(UserAccessService.bearer(req), runner, secret).secret);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @PostMapping("/pair/start")
    public Map<String, Object> startPair(HttpServletRequest req) {
        requireConsole(req);
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        String claim = Credentials.token("jhp_");
        long expiresAt = System.currentTimeMillis() + 5 * 60000L;
        String ua = Objects.toString(req.getHeader("User-Agent"), "");
        String browser =
                ua.contains("Edg/")
                        ? "Edge"
                        : ua.contains("Firefox/")
                                ? "Firefox"
                                : ua.contains("Chrome/")
                                        ? "Chrome"
                                        : ua.contains("Safari/") ? "Safari" : "Browser";
        if (!service.users.startPair(code.toString(), claim, browser, expiresAt))
            throw new ProtocolException(429, "PAIR_BUSY", "Try pairing again in a few minutes");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code.substring(0, 4) + "-" + code.substring(4));
        result.put("claim", claim);
        result.put("expiresAt", expiresAt);
        return result;
    }

    @PostMapping("/pair/{action:info|approve}")
    public Map<String, String> runnerPair(
            @PathVariable String action,
            @RequestBody Map<String, String> body,
            HttpServletRequest req,
            @RequestHeader("X-Runner-Id") String runner,
            @RequestHeader("X-Runner-Secret") String secret) {
        String token = UserAccessService.bearer(req);
        UserStore.User user = service.runnerUser(token, runner, secret);
        String code =
                Objects.toString(body.get("code"), "").toUpperCase(Locale.ROOT).replace("-", "");
        String browser = code.matches("[A-Z2-9]{8}") ? service.users.pairingBrowser(code) : null;
        if (browser == null) throw pairExpired();
        if ("approve".equals(action)
                && !service.users.approvePair(code, user.userId, Credentials.hash(token)))
            throw pairExpired();
        return Collections.singletonMap("browser", browser);
    }

    @PostMapping("/pair/poll")
    public Map<String, Boolean> pollPair(
            @RequestBody Map<String, String> body,
            HttpServletRequest req,
            HttpServletResponse res) {
        requireConsole(req);
        String claim = body.get("claim");
        if (claim == null || !claim.matches("jhp_[A-Za-z0-9_-]{43}")) throw pairExpired();
        if (service.users.pendingPair(claim)) return Collections.singletonMap("accepted", false);
        if (service.users.findAccess(claim, "ticket") == null) throw pairExpired();
        return finishExchange(claim, req, res);
    }

    private ProtocolException pairExpired() {
        return new ProtocolException(
                410, "PAIR_EXPIRED", "Pairing code expired or already used. Request a new code.");
    }

    private void requireConsole(HttpServletRequest req) {
        if (!"1".equals(req.getHeader("X-JAgent-Console")))
            throw new ProtocolException(403, "CSRF", "Open the Console to sign in");
    }

    @PostMapping("/exchange")
    public Map<String, Boolean> exchange(
            @RequestBody Map<String, String> body,
            HttpServletRequest req,
            HttpServletResponse res) {
        requireConsole(req);
        return finishExchange(body.get("ticket"), req, res);
    }

    private Map<String, Boolean> finishExchange(
            String secret, HttpServletRequest req, HttpServletResponse res) {
        UserStore.Access old = service.browser(req);
        UserStore.Access ticket = service.users.findAccess(secret, "ticket");
        if (old != null && ticket != null && !old.userId.equals(ticket.userId))
            throw new ProtocolException(
                    409, "DIFFERENT_USER", "Use a separate browser profile for another user");
        UserStore.Access a = service.exchange(secret);
        UserAccessService.cookie(req, res, a.secret, UserAccessService.SESSION_SECONDS);
        res.setHeader("Cache-Control", "no-store");
        return Collections.singletonMap("accepted", true);
    }
}
