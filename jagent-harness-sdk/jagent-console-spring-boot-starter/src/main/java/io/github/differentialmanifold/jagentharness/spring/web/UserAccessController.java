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
        boolean ready = a != null && service.valid(a);
        result.put("authenticated", ready);
        result.put("insecure", !req.isSecure());
        if (ready) {
            result.put("user", service.users.get(a.userId));
            result.put("runnerId", a.runnerId);
        }
        return result;
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

    @PostMapping("/exchange")
    public Map<String, Boolean> exchange(
            @RequestBody Map<String, String> body,
            HttpServletRequest req,
            HttpServletResponse res) {
        // A fetch from our console must opt in; cross-site forms cannot exchange a ticket.
        if (!"1".equals(req.getHeader("X-JAgent-Console")))
            throw new ProtocolException(403, "CSRF", "Open the console from your Runner");
        UserStore.Access old = service.browser(req);
        UserStore.Access ticket = service.users.findAccess(body.get("ticket"), "ticket");
        if (old != null && ticket != null && !old.userId.equals(ticket.userId))
            throw new ProtocolException(
                    409, "DIFFERENT_USER", "Use a separate browser profile for another user");
        UserStore.Access a = service.exchange(body.get("ticket"));
        UserAccessService.cookie(req, res, a.secret, 12 * 60 * 60);
        res.setHeader("Cache-Control", "no-store");
        return Collections.singletonMap("accepted", true);
    }
}
