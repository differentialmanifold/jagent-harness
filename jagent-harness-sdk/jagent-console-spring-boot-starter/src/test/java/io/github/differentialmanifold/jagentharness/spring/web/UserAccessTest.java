package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:user-access;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "harness.identity.enabled=true",
            "harness.model.provider=user-test",
            "harness.model.model=test",
            "harness.model.retry.enabled=false",
            "harness.compaction.enabled=false",
            "harness.personal.learning.enabled=false",
            "harness.store.jdbc.application-id=users-test"
        })
@Import(UserAccessTest.Fixtures.class)
class UserAccessTest {
    @Autowired UserStore users;
    @Autowired PersonalAgentService agents;
    @Autowired PersonalAgentStore store;
    @Autowired KnowledgeFileStore files;
    @Autowired Model model;
    @Autowired UserAccessService access;
    @org.springframework.boot.web.server.LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();

    String base() {
        return "http://127.0.0.1:" + port;
    }

    String token(String name) {
        return users.issueToken(users.create(name).userId);
    }

    Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    Response call(
            String method,
            String path,
            String token,
            String cookie,
            Object body,
            Map<String, String> headers)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (headers != null) headers.forEach(c::setRequestProperty);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.writeValueAsBytes(body));
            }
        }
        int status = c.getResponseCode();
        String setCookie = c.getHeaderField("Set-Cookie");
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        JsonNode data = in == null ? json.nullNode() : json.readTree(in);
        c.disconnect();
        return new Response(status, data, setCookie);
    }

    JsonNode api(String method, String path, String token, Object body) throws Exception {
        Response r = call(method, path, token, null, body, null);
        assertEquals(200, r.status, String.valueOf(r.body));
        return r.body;
    }

    void register(String token) throws Exception {
        apiWithSecret(
                "POST",
                "/api/v1/runners",
                token,
                map(
                        "runnerId",
                        "same_runner",
                        "name",
                        "Desktop",
                        "tools",
                        Collections.singletonList(
                                map("name", "read", "parameters", map("type", "object")))));
    }

    JsonNode apiWithSecret(String method, String path, String token, Object body) throws Exception {
        Response r =
                call(
                        method,
                        path,
                        token,
                        null,
                        body,
                        mapStrings(
                                "X-Runner-Id",
                                "same_runner",
                                "X-Runner-Secret",
                                "a-test-secret-long-enough-for-registration"));
        assertEquals(200, r.status, String.valueOf(r.body));
        return r.body;
    }

    Map<String, String> mapStrings(String... values) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) m.put(values[i], values[i + 1]);
        return m;
    }

    String browser(String token) throws Exception {
        String ticket =
                apiWithSecret("POST", "/api/v1/access/browser", token, map())
                        .path("ticket")
                        .asText();
        Response r =
                call(
                        "POST",
                        "/api/v1/access/exchange",
                        null,
                        null,
                        map("ticket", ticket),
                        mapStrings("X-JAgent-Console", "1"));
        assertEquals(200, r.status, String.valueOf(r.body));
        assertFalse(r.cookie.contains("Secure"));
        assertTrue(r.cookie.contains("HttpOnly"));
        assertTrue(r.cookie.contains("SameSite=Strict"));
        assertEquals(
                401,
                call(
                                "POST",
                                "/api/v1/access/exchange",
                                null,
                                null,
                                map("ticket", ticket),
                                mapStrings("X-JAgent-Console", "1"))
                        .status);
        return r.cookie.split(";")[0];
    }

    @Test
    void onlyInitiatingBrowserCanRedeemRunnerApprovalOnce() throws Exception {
        String token = token("Pairing user");
        register(token);
        String path = "/api/v1/access/pair/";
        Map<String, String> console = mapStrings("X-JAgent-Console", "1");
        assertEquals(403, call("POST", path + "start", null, null, map(), null).status);
        Response start = call("POST", path + "start", null, null, map(), console);
        assertEquals(200, start.status);
        String code = start.body.get("code").asText(), claim = start.body.get("claim").asText();
        assertFalse(
                call("POST", path + "poll", null, null, map("claim", claim), console)
                        .body
                        .get("accepted")
                        .asBoolean());
        assertEquals(
                401, call("POST", path + "approve", null, null, map("code", code), null).status);
        apiWithSecret("POST", path + "info", token, map("code", code));
        apiWithSecret("POST", path + "approve", token, map("code", code));
        assertEquals(
                410, call("POST", path + "poll", null, null, map("claim", code), console).status);
        Response accepted = call("POST", path + "poll", null, null, map("claim", claim), console);
        assertEquals(200, accepted.status);
        assertTrue(accepted.cookie.contains("HttpOnly"));
        assertEquals(
                users.authenticate(token).userId,
                call(
                                "GET",
                                "/api/v1/access/status",
                                null,
                                accepted.cookie.split(";")[0],
                                null,
                                null)
                        .body
                        .path("user")
                        .path("userId")
                        .asText());
        assertEquals(
                410, call("POST", path + "poll", null, null, map("claim", claim), console).status);
    }

    @Test
    void tokensIsolateDataAndBrowserAccessEndsOnDisconnectAndRevocation() throws Exception {
        String a = token("Alice"), b = token("Bob");
        register(a);
        register(b);
        assertEquals(401, call("GET", "/api/v1/personal/agents", null, null, null, null).status);
        assertEquals(
                401, call("GET", "/api/v1/personal/agents", "invented", null, null, null).status);
        assertEquals(1, api("GET", "/api/v1/runners", a, null).size());
        String aId = users.authenticate(a).userId, bId = users.authenticate(b).userId;
        try (UserContext ignored = UserContext.enter(aId)) {
            PersonalAgentStore.Profile p = agents.require("agent_mainbot");
            p.name = "Alice mainbot";
            agents.save(p);
            files.writeFile(
                    KnowledgeScope.agentGlobal(), "AGENTS.md", "Alice secret", "text/plain");
        }
        try (UserContext ignored = UserContext.enter(bId)) {
            assertEquals("mainbot", agents.require("agent_mainbot").name);
            assertNull(files.readFile(KnowledgeScope.agentGlobal(), "AGENTS.md"));
        }
        String ses =
                api("GET", "/api/v1/personal/agents/agent_mainbot/conversations", a, null)
                        .get(0)
                        .path("sessionId")
                        .asText();
        assertEquals(
                404,
                call("POST", "/api/v1/sessions/detail", b, null, map("sessionId", ses), null)
                        .status);
        String cookie = browser(a);
        assertEquals(
                "Alice mainbot",
                call("GET", "/api/v1/personal/agents", null, cookie, null, null)
                        .body
                        .get(0)
                        .path("name")
                        .asText());
        org.springframework.mock.web.MockHttpServletRequest cross =
                new org.springframework.mock.web.MockHttpServletRequest(
                        "POST", "/api/v1/personal/agents");
        cross.setCookies(
                new javax.servlet.http.Cookie(
                        "jagent_session", cookie.substring(cookie.indexOf('=') + 1)));
        cross.addHeader("Sec-Fetch-Site", "cross-site");
        org.springframework.mock.web.MockHttpServletResponse denied =
                new org.springframework.mock.web.MockHttpServletResponse();
        new ApiAuthenticationFilter(access)
                .doFilter(
                        cross,
                        denied,
                        (req, res) -> fail("Cross-site mutation must not reach a controller"));
        assertEquals(403, denied.getStatus());
        apiWithSecret("POST", "/api/v1/runners/same_runner/disconnect", a, map());
        assertEquals(503, call("GET", "/api/v1/personal/agents", null, cookie, null, null).status);
        assertTrue(
                call("GET", "/api/v1/access/status", null, cookie, null, null)
                        .body
                        .path("authenticated")
                        .asBoolean());
        register(a);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, cookie, null, null).status);
        String replacement = users.issueToken(aId);
        assertEquals(401, call("GET", "/api/v1/personal/agents", a, null, null, null).status);
        assertEquals(401, call("GET", "/api/v1/personal/agents", null, cookie, null, null).status);
        assertEquals(
                ses,
                api("GET", "/api/v1/personal/agents/agent_mainbot/conversations", replacement, null)
                        .get(0)
                        .path("sessionId")
                        .asText());
        assertEquals(
                403, call("GET", "/api/v1/personal/agents", replacement, null, null, null).status);
        assertEquals(
                403,
                call(
                                "POST",
                                "/api/v1/personal/agents",
                                replacement,
                                null,
                                map("name", "Helper"),
                                null)
                        .status);
        assertEquals(
                403,
                call(
                                "GET",
                                "/api/v1/personal/agents/another/conversations",
                                replacement,
                                null,
                                null,
                                null)
                        .status);
        users.revoke(aId);
        assertEquals(
                401, call("GET", "/api/v1/personal/agents", replacement, null, null, null).status);
        assertEquals(
                200,
                call(
                                "GET",
                                "/api/v1/personal/agents/agent_mainbot/conversations",
                                b,
                                null,
                                null,
                                null)
                        .status);
    }

    @Test
    void devicesShareMainbotAvailabilityAndCanBeRevokedIndependently() throws Exception {
        String token = token("Devices");
        register(token);
        Map<String, String> secondary =
                mapStrings(
                        "X-Runner-Id",
                        "secondary",
                        "X-Runner-Secret",
                        "another-runner-secret-long-enough");
        assertEquals(
                200,
                call(
                                "POST",
                                "/api/v1/runners",
                                token,
                                null,
                                map(
                                        "runnerId",
                                        "secondary",
                                        "name",
                                        "Other computer",
                                        "tools",
                                        Collections.singletonList(
                                                map(
                                                        "name",
                                                        "read",
                                                        "parameters",
                                                        map("type", "object")))),
                                secondary)
                        .status);
        String ticket =
                call("POST", "/api/v1/access/browser", token, null, map(), secondary)
                        .body
                        .path("ticket")
                        .asText();
        Response first =
                call(
                        "POST",
                        "/api/v1/access/exchange",
                        null,
                        null,
                        map("ticket", ticket),
                        mapStrings("X-JAgent-Console", "1"));
        assertEquals(200, first.status);
        String a = first.cookie.split(";")[0];
        Response shared = call("POST", "/api/v1/access/devices/link", null, a, map(), null);
        assertEquals(200, shared.status);
        String link = shared.body.path("ticket").asText();
        Response second =
                call(
                        "POST",
                        "/api/v1/access/exchange",
                        null,
                        null,
                        map("ticket", link),
                        mapStrings("X-JAgent-Console", "1"));
        assertEquals(200, second.status);
        String b = second.cookie.split(";")[0];
        assertNotEquals(a, b);
        assertEquals(
                401,
                call(
                                "POST",
                                "/api/v1/access/exchange",
                                null,
                                null,
                                map("ticket", link),
                                mapStrings("X-JAgent-Console", "1"))
                        .status);
        assertEquals(
                200,
                call("POST", "/api/v1/runners/secondary/disconnect", token, null, map(), secondary)
                        .status);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, a, null, null).status);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, b, null, null).status);
        try (UserContext ignored = UserContext.enter(users.authenticate(token).userId)) {
            PersonalAgentStore.Profile mainbot = agents.require("agent_mainbot");
            mainbot.enabled = false;
            agents.save(mainbot);
        }
        assertEquals(
                200,
                call("GET", "/api/v1/personal/agents", null, b, null, null).status,
                "Pausing must not lock the user out");
        apiWithSecret("POST", "/api/v1/runners/same_runner/disconnect", token, map());
        for (String cookie : Arrays.asList(a, b)) {
            Response status = call("GET", "/api/v1/access/status", null, cookie, null, null);
            assertTrue(status.body.path("authenticated").asBoolean());
            assertFalse(status.body.path("mainbotOnline").asBoolean());
            Response locked = call("GET", "/api/v1/personal/agents", null, cookie, null, null);
            assertEquals(503, locked.status);
            assertEquals("MAINBOT_OFFLINE", locked.body.path("code").asText());
        }
        // Another Runner becoming online cannot unlock mainbot's workspace.
        assertEquals(
                200,
                call(
                                "POST",
                                "/api/v1/runners",
                                token,
                                null,
                                map(
                                        "runnerId",
                                        "secondary",
                                        "name",
                                        "Other computer",
                                        "tools",
                                        Collections.singletonList(
                                                map(
                                                        "name",
                                                        "read",
                                                        "parameters",
                                                        map("type", "object")))),
                                secondary)
                        .status);
        assertFalse(
                call("GET", "/api/v1/access/status", null, b, null, null)
                        .body
                        .path("mainbotOnline")
                        .asBoolean());
        register(token);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, b, null, null).status);
        JsonNode devices = call("GET", "/api/v1/access/devices", null, a, null, null).body;
        assertEquals(2, devices.size());
        String other = null;
        for (JsonNode device : devices)
            if (!device.path("current").asBoolean()) other = device.path("id").asText();
        assertNotNull(other);
        assertFalse(devices.toString().contains("jhs_"));
        // A different user cannot list or revoke this user's browser session.
        String stranger = token("Stranger");
        register(stranger);
        String strangerCookie = browser(stranger);
        assertEquals(
                1,
                call("GET", "/api/v1/access/devices", null, strangerCookie, null, null)
                        .body
                        .size());
        assertEquals(
                200,
                call("DELETE", "/api/v1/access/devices/" + other, null, strangerCookie, null, null)
                        .status);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, b, null, null).status);
        assertEquals(
                200, call("DELETE", "/api/v1/access/devices/" + other, null, a, null, null).status);
        assertEquals(401, call("GET", "/api/v1/personal/agents", null, b, null, null).status);
        assertEquals(200, call("GET", "/api/v1/personal/agents", null, a, null, null).status);
        String current =
                call("GET", "/api/v1/access/devices", null, a, null, null)
                        .body
                        .get(0)
                        .path("id")
                        .asText();
        assertEquals(
                200,
                call("DELETE", "/api/v1/access/devices/" + current, null, a, null, null).status);
        assertFalse(
                call("GET", "/api/v1/access/status", null, a, null, null)
                        .body
                        .path("authenticated")
                        .asBoolean());
    }

    @Test
    void operatorLinkAuthenticatesWithoutRunnerButCannotOpenWorkspace() throws Exception {
        String token = token("Headless");
        String user = users.authenticate(token).userId;
        UserStore.Access ticket = users.createTicket(user, null);
        Response response =
                call(
                        "POST",
                        "/api/v1/access/exchange",
                        null,
                        null,
                        map("ticket", ticket.secret),
                        mapStrings("X-JAgent-Console", "1"));
        assertEquals(200, response.status);
        String cookie = response.cookie.split(";")[0];
        assertTrue(
                call("GET", "/api/v1/access/status", null, cookie, null, null)
                        .body
                        .path("authenticated")
                        .asBoolean());
        assertEquals(503, call("GET", "/api/v1/personal/agents", null, cookie, null, null).status);
        register(token);
        assertTrue(
                call("GET", "/api/v1/access/status", null, cookie, null, null)
                        .body
                        .path("mainbotOnline")
                        .asBoolean());
        // Outstanding links and sessions must both be invalidated by a user-token reset.
        UserStore.Access outstanding = users.createTicket(user, Credentials.hash(token));
        users.issueToken(user);
        assertThrows(
                IllegalArgumentException.class,
                () -> users.createTicket(user, Credentials.hash(token)));
        assertEquals(
                401,
                call(
                                "POST",
                                "/api/v1/access/exchange",
                                null,
                                null,
                                map("ticket", outstanding.secret),
                                mapStrings("X-JAgent-Console", "1"))
                        .status);
    }

    @Test
    void twoMainbotsRunConcurrentlyAndBackgroundThreadsKeepTheirUser() throws Exception {
        String a = token("Concurrent A"), b = token("Concurrent B");
        register(a);
        register(b);
        String sa =
                api("GET", "/api/v1/personal/agents/agent_mainbot/conversations", a, null)
                        .get(0)
                        .path("sessionId")
                        .asText();
        String sb =
                api("GET", "/api/v1/personal/agents/agent_mainbot/conversations", b, null)
                        .get(0)
                        .path("sessionId")
                        .asText();
        model.entered = new CountDownLatch(2);
        model.release = new CountDownLatch(1);
        try {
            api(
                    "POST",
                    "/api/v1/personal/agents/agent_mainbot/conversations/" + sa,
                    a,
                    map("text", "hold"));
            api(
                    "POST",
                    "/api/v1/personal/agents/agent_mainbot/conversations/" + sb,
                    b,
                    map("text", "hold"));
            assertTrue(
                    model.entered.await(8, TimeUnit.SECONDS),
                    "Different users' mainbots must run concurrently");
        } finally {
            model.release.countDown();
        }
        for (String[] pair : new String[][] {{a, sa}, {b, sb}}) {
            String user = users.authenticate(pair[0]).userId;
            long until = System.currentTimeMillis() + 8000;
            boolean done = false;
            while (System.currentTimeMillis() < until) {
                try (UserContext ignored = UserContext.enter(user)) {
                    List<PersonalAgentStore.Inbox> messages = store.messages(pair[1]);
                    if (!messages.isEmpty() && "DONE".equals(messages.get(0).status)) {
                        assertEquals(user, messages.get(0).answer);
                        done = true;
                        break;
                    }
                }
                Thread.sleep(30);
            }
            assertTrue(
                    done,
                    "Background result must be written to the originating user's conversation");
        }
    }

    static class Response {
        final int status;
        final JsonNode body;
        final String cookie;

        Response(int s, JsonNode b, String c) {
            status = s;
            body = b;
            cookie = c;
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        Model model() {
            return new Model();
        }
    }

    static class Model implements ModelProvider {
        volatile CountDownLatch entered, release;

        public String getName() {
            return "user-test";
        }

        public ModelResponse chat(ModelRequest request) {
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test model timeout");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            ModelResponse r = new ModelResponse();
            r.setContent("{\"userReply\":\"" + UserContext.current() + "\"}");
            return r;
        }
    }
}
