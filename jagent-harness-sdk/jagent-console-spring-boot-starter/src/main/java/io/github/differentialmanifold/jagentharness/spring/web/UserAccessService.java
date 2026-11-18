package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentException;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentService;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.protocol.RunnerInfo;
import java.util.*;
import javax.servlet.http.*;

/**
 * Browser identity is independent of execution; workspace access requires the user's mainbot
 * Runner.
 */
public final class UserAccessService {
    static final String COOKIE = "jagent_session", ATTRIBUTE = UserAccessService.class.getName();
    static final int SESSION_SECONDS = 30 * 24 * 60 * 60;
    final UserStore users;
    private final RunnerService runners;
    private final PersonalAgentService agents;

    public UserAccessService(UserStore users, RunnerService runners, PersonalAgentService agents) {
        this.users = users;
        this.runners = runners;
        this.agents = agents;
    }

    public UserStore.Access browserTicket(String token, String runner, String secret) {
        UserStore.User user = runnerUser(token, runner, secret);
        try (UserContext ignored = UserContext.enter(user.userId)) {
            agents.bindMainbotIfUnbound(runner);
            return users.createTicket(user.userId, Credentials.hash(token));
        }
    }

    UserStore.User runnerUser(String token, String runner, String secret) {
        UserStore.User user = users.authenticate(token);
        if (user == null) throw unauthorized();
        try (UserContext ignored = UserContext.enter(user.userId)) {
            runners.authenticate(runner, secret);
            return user;
        }
    }

    public UserStore.Access exchange(String ticket) {
        UserStore.Access a = users.consumeTicket(ticket);
        if (a == null) throw unauthorized();
        a.secret = Credentials.token("jhs_");
        a.kind = "browser";
        a.expiresAt = System.currentTimeMillis() + SESSION_SECONDS * 1000L;
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
        return users.valid(a);
    }

    public boolean workspaceReady(String userId) {
        try (UserContext ignored = UserContext.enter(userId)) {
            String runner = agents.require(PersonalAgentService.MAINBOT_ID).runnerId;
            return runner != null && online(runners.require(runner));
        } catch (ProtocolException e) {
            if (e.status != 404) throw e;
            return false;
        } catch (PersonalAgentException e) {
            if (e.kind != PersonalAgentException.Kind.NOT_FOUND) throw e;
            return false;
        }
    }

    public UserStore.Access requireBrowser(HttpServletRequest req) {
        UserStore.Access a = browser(req);
        if (a == null || !valid(a)) throw unauthorized();
        return a;
    }

    private boolean online(RunnerInfo info) {
        return info.lastSeen > 0 && System.currentTimeMillis() - info.lastSeen < 65000;
    }

    static ProtocolException unauthorized() {
        return new ProtocolException(
                401,
                "UNAUTHORIZED",
                "Open a fresh access link from your Runner or an authorized browser");
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
