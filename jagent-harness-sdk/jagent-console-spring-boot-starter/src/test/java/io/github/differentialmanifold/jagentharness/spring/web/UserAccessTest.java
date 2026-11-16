package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.client.AgentRunner;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.identity.*;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
    @TempDir Path home;
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
        assertEquals(401, call("GET", "/api/v1/personal/agents", null, cookie, null, null).status);
        assertFalse(
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
                "Alice mainbot",
                api("GET", "/api/v1/personal/agents", replacement, null)
                        .get(0)
                        .path("name")
                        .asText());
        users.revoke(aId);
        assertEquals(
                401, call("GET", "/api/v1/personal/agents", replacement, null, null, null).status);
        assertEquals(200, call("GET", "/api/v1/personal/agents", b, null, null, null).status);
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

    @Test
    void sdkUsesDistinctStateForUsersAndOpensOnlyAShortLivedConsoleTicket() throws Exception {
        String a = token("SDK A"), b = token("SDK B");
        AtomicReference<String> link = new AtomicReference<>();
        ToolDefinition tool =
                new ToolDefinition() {
                    public String getName() {
                        return "read";
                    }

                    public String getDescription() {
                        return "Test";
                    }

                    public JsonNode getParametersSchema() {
                        return json.createObjectNode().put("type", "object");
                    }

                    public ToolExecutionResult execute(ToolContext c, JsonNode v) {
                        return ToolExecutionResult.success("ok");
                    }
                };
        try (AgentRunner ra =
                        AgentRunner.builder(base(), home)
                                .userToken(a)
                                .tools(Collections.singletonList(tool))
                                .onConsole(base(), link::set)
                                .build();
                AgentRunner rb =
                        AgentRunner.builder(base(), home)
                                .userToken(b)
                                .tools(Collections.singletonList(tool))
                                .build()) {
            assertNotEquals(ra.stateDirectory(), rb.stateDirectory());
            assertNotEquals(ra.runnerId(), rb.runnerId());
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> running =
                    worker.submit(
                            () -> {
                                try {
                                    ra.serve();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            try {
                long end = System.currentTimeMillis() + 5000;
                while (link.get() == null && System.currentTimeMillis() < end) Thread.sleep(20);
                assertNotNull(link.get());
                assertFalse(link.get().contains(a));
                assertTrue(link.get().contains("#jagent-ticket=jht_"));
            } finally {
                ra.close();
                running.get(5, TimeUnit.SECONDS);
                worker.shutdownNow();
            }
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
