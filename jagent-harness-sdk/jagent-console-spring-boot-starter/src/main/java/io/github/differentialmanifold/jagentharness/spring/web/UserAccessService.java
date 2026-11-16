package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentService;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.protocol.RunnerInfo;
import java.util.*;
import javax.servlet.http.*;

/**
 * Browser sessions are distinct from user tokens and require their launching Runner to be online.
 */
public final class UserAccessService {
    static final String COOKIE = "jagent_session", ATTRIBUTE = UserAccessService.class.getName();
    final UserStore users;
    private final RunnerService runners;
    private final PersonalAgentService agents;

    public UserAccessService(UserStore users, RunnerService runners, PersonalAgentService agents) {
        this.users = users;
        this.runners = runners;
        this.agents = agents;
    }

    public UserStore.Access browserTicket(String token, String runner, String secret) {
        UserStore.User user = users.authenticate(token);
        if (user == null) throw unauthorized();
        try (UserContext ignored = UserContext.enter(user.userId)) {
            runners.authenticate(runner, secret);
            RunnerInfo info = runners.require(runner);
            if (!online(info))
                throw new ProtocolException(
                        401, "RUNNER_OFFLINE", "Start your local Runner to open the console");
            agents.bindMainbotIfUnbound(runner);
            UserStore.Access a = new UserStore.Access();
            a.secret = Credentials.token("jht_");
            a.kind = "ticket";
            a.userId = user.userId;
            a.runnerId = runner;
            a.credentialHash = Credentials.hash(token);
            a.expiresAt = System.currentTimeMillis() + 60000;
            users.saveAccess(a);
            return a;
        }
    }

    public UserStore.Access exchange(String ticket) {
        UserStore.Access a = users.consumeTicket(ticket);
        if (a == null || !runnerOnline(a)) throw unauthorized();
        a.secret = Credentials.token("jhs_");
        a.kind = "browser";
        a.expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L;
        users.saveAccess(a);
        return a;
    }

    public UserStore.Access browser(HttpServletRequest req) {
        if (req.getCookies() != null)
            for (Cookie cookie : req.getCookies())
                if (COOKIE.equals(cookie.getName()))
                    return users.findAccess(cookie.getValue(), "browser");
        return null;
    }

    public boolean valid(UserStore.Access a) {
        return users.valid(a) && runnerOnline(a);
    }

    private boolean runnerOnline(UserStore.Access a) {
        try (UserContext ignored = UserContext.enter(a.userId)) {
            return online(runners.require(a.runnerId));
        } catch (ProtocolException e) {
            return false;
        }
    }

    private boolean online(RunnerInfo info) {
        return info.lastSeen > 0 && System.currentTimeMillis() - info.lastSeen < 65000;
    }

    static ProtocolException unauthorized() {
        return new ProtocolException(
                401,
                "UNAUTHORIZED",
                "Start your local Runner with a valid user token, then open the console from that Runner");
    }

    static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }

    static void cookie(HttpServletRequest req, HttpServletResponse res, String value, int age) {
        res.addHeader(
                "Set-Cookie",
                COOKIE
                        + "="
                        + value
                        + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                        + age
                        + (req.isSecure() ? "; Secure" : ""));
    }
}
