package io.github.differentialmanifold.jagentharness.spring.personal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.channel.*;
import com.lark.oapi.channel.model.*;
import com.lark.oapi.scene.registration.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeishuServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final PersonalAgentService agents = mock(PersonalAgentService.class);
    final PersonalAgentStore store = mock(PersonalAgentStore.class);
    final LarkChannel channel = mock(LarkChannel.class);
    final Map<String, ChannelEventHandler<Object>> handlers = new ConcurrentHashMap<>();
    final Map<String, Inbox> accepted = new ConcurrentHashMap<>();
    volatile String saved;

    FeishuService fixture(FeishuService.Registrar registrar) {
        Profile p = new Profile();
        p.id = "a";
        p.name = "Assistant";
        when(agents.require("a")).thenReturn(p);
        when(store.profiles()).thenReturn(Collections.singletonList(p));
        when(store.channel("a")).thenAnswer(call -> saved);
        Conversation c = new Conversation();
        c.agentId = "a";
        c.sessionId = "conversation";
        when(agents.newConversation("a", "Feishu")).thenReturn(c);
        when(store.conversation(anyString())).thenReturn(c);
        doAnswer(
                        call -> {
                            saved = call.getArgument(1);
                            return null;
                        })
                .when(store)
                .channel(eq("a"), nullable(String.class));
        when(store.enqueue(any()))
                .thenAnswer(
                        call -> {
                            Inbox m = call.getArgument(0);
                            return accepted.putIfAbsent(m.id, m) == null;
                        });
        when(channel.connect()).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(
                        call -> {
                            handlers.put(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(channel)
                .on(anyString(), any(ChannelEventHandler.class));
        return new FeishuService(
                agents,
                store,
                json,
                registrar,
                options -> {
                    assertEquals("websocket", options.getTransport());
                    return channel;
                });
    }

    NormalizedMessage message(String id, String user, String kind, String text) {
        return new NormalizedMessage(
                id,
                "oc_chat",
                kind,
                user,
                "User",
                text,
                "text",
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                System.currentTimeMillis(),
                null);
    }

    @Test
    void deliversOnlyRepliesToTheirOriginalChannelIncludingBackgroundResults() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            throw new AssertionError();
                        })) {
            FeishuService.Configuration config = new FeishuService.Configuration();
            config.appId = "app";
            config.appSecret = "secret";
            config.ownerId = "ou_owner";
            config.sessionId = "conversation";
            service.connect("a", config);
            handlers.get("message").handle(message("input", "ou_owner", "p2p", "Do the work"));
            Inbox input = accepted.values().iterator().next();
            assertEquals("app", input.replyTo.accountId);
            assertEquals("ou_owner", input.replyTo.recipientId);
            input.status = "DONE";
            input.answer = "Started";
            Inbox result = reply("PROJECT", "Verified", input.replyTo);
            Inbox check = reply("CHECK", "", input.replyTo);
            Inbox web = reply("USER", "Web only", null);
            Inbox wrongApp =
                    reply(
                            "PROJECT",
                            "Other app",
                            new ReplyTarget("feishu", "previous-app", "ou_owner"));
            Inbox wrongOwner =
                    reply(
                            "CHECK",
                            "Other user",
                            new ReplyTarget("feishu", "app", "previous-owner"));
            when(store.inbox("DONE"))
                    .thenReturn(Arrays.asList(input, result, check, web, wrongApp, wrongOwner));
            when(channel.send(anyString(), any(SendInput.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            service.deliver();
            verify(channel, times(2)).send(eq("ou_owner"), any(SendInput.class));
            assertTrue(result.delivered);
            assertTrue(check.delivered);
            assertNotNull(wrongApp.deliveryError);
            assertNotNull(wrongOwner.deliveryError);
            service.deliver();
            verify(channel, times(2)).send(anyString(), any(SendInput.class));
        }
    }

    private Inbox reply(String source, String answer, ReplyTarget target) {
        Inbox m = new Inbox();
        m.id = UUID.randomUUID().toString();
        m.agentId = "a";
        m.source = source;
        m.status = "DONE";
        m.answer = answer;
        m.replyTo = target;
        return m;
    }

    @Test
    void nextFeishuMessageStartsANewChatAfterDeletion() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            throw new AssertionError();
                        })) {
            FeishuService.Configuration config = new FeishuService.Configuration();
            config.appId = "app";
            config.appSecret = "secret";
            config.ownerId = "ou_owner";
            config.sessionId = "deleted";
            service.connect("a", config);
            when(store.conversation("deleted")).thenReturn(null);
            handlers.get("message").handle(message("after-delete", "ou_owner", "p2p", "Continue"));
            assertEquals("conversation", accepted.values().iterator().next().sessionId);
            assertEquals("conversation", json.readTree(saved).path("sessionId").asText());
            verify(agents, times(1)).newConversation("a", "Feishu");
        }
    }

    @Test
    void existingAppRequiresPairingAndOnlyAcceptsOwnerDirectMessages() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            throw new AssertionError("No registration expected");
                        })) {
            FeishuService.Configuration config = new FeishuService.Configuration();
            config.appId = "cli_demo";
            config.appSecret = "test-secret-never-in-profile";
            Map<String, Object> status = service.connect("a", config);
            assertEquals("PAIRING", status.get("status"));
            assertFalse(json.writeValueAsString(status).contains(config.appSecret));
            assertTrue(saved.contains(config.appSecret));
            handlers.get("message").handle(message("first", "ou_other", "p2p", "hello"));
            assertTrue(accepted.isEmpty());
            handlers.get("message")
                    .handle(message("pair", "ou_owner", "p2p", (String) status.get("pairCode")));
            assertEquals("CONNECTED", service.status("a").get("status"));
            assertNull(service.status("a").get("pairCode"));
            handlers.get("message")
                    .handle(message("group", "ou_owner", "group", "not a private request"));
            handlers.get("message")
                    .handle(message("outsider", "ou_other", "p2p", "not authorized"));
            assertTrue(accepted.isEmpty());
            NormalizedMessage input = message("one", "ou_owner", "p2p", "Plan an experiment");
            handlers.get("message").handle(input);
            handlers.get("message").handle(input);
            assertEquals(1, accepted.size());
            assertEquals("FEISHU", accepted.values().iterator().next().source);
            service.disconnect("a");
            assertNotNull(saved);
            assertFalse(json.readTree(saved).path("enabled").asBoolean(true));
            assertEquals("DISCONNECTED", service.status("a").get("status"));
            assertEquals(true, service.status("a").get("configured"));
            assertEquals(true, service.status("a").get("paired"));
            assertEquals("cli_demo", service.status("a").get("appId"));
            handlers.get("message").handle(message("late", "ou_owner", "p2p", "late event"));
            assertEquals(1, accepted.size());
            ChannelEventHandler<Object> oldHandler = handlers.get("message");
            Map<String, Object> restored = service.reconnect("a");
            assertEquals("CONNECTED", restored.get("status"));
            assertNull(restored.get("pairCode"));
            assertFalse(json.writeValueAsString(restored).contains(config.appSecret));
            assertTrue(json.readTree(saved).path("enabled").asBoolean());
            oldHandler.handle(message("stale", "ou_owner", "p2p", "stale connection"));
            assertEquals(1, accepted.size());
            handlers.get("message").handle(message("next", "ou_owner", "p2p", "Continue"));
            assertEquals(2, accepted.size());
            verify(agents, times(1)).newConversation("a", "Feishu");
            verify(channel, never()).send(anyString(), any(SendInput.class));
        }
    }

    @Test
    void qrRegistrationUsesAuthorizedIdentityAndDoesNotExposeCredentials() throws Exception {
        CountDownLatch authorized = new CountDownLatch(1);
        AtomicInteger registrations = new AtomicInteger();
        try (FeishuService service =
                fixture(
                        options -> {
                            registrations.incrementAndGet();
                            assertNotNull(options.getAddons());
                            options.getOnQRCode()
                                    .accept(
                                            new QRCodeInfo(
                                                    "https://open.feishu.cn/test-authorization",
                                                    600));
                            authorized.await(2, TimeUnit.SECONDS);
                            return new RegisterAppResult(
                                    "cli_qr",
                                    "private-test-secret",
                                    new UserInfo("ou_authorized", "feishu"));
                        })) {
            service.register("a");
            long deadline = System.currentTimeMillis() + 3000;
            while (service.status("a").get("authorizationUrl") == null
                    && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(
                    "https://open.feishu.cn/test-authorization",
                    service.status("a").get("authorizationUrl"));
            authorized.countDown();
            while (!"CONNECTED".equals(service.status("a").get("status"))
                    && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals("CONNECTED", service.status("a").get("status"));
            assertFalse(
                    json.writeValueAsString(service.status("a")).contains("private-test-secret"));
            handlers.get("message").handle(message("untrusted", "ou_other", "p2p", "hello"));
            assertTrue(accepted.isEmpty());
            handlers.get("message").handle(message("trusted", "ou_authorized", "p2p", "hello"));
            assertEquals(1, accepted.size());
            service.disconnect("a");
            assertEquals("cli_qr", service.status("a").get("appId"));
            Map<String, Object> restored = service.reconnect("a");
            assertEquals("CONNECTED", restored.get("status"));
            assertEquals("cli_qr", restored.get("appId"));
            assertEquals("conversation", restored.get("sessionId"));
            assertNull(restored.get("pairCode"));
            handlers.get("message")
                    .handle(message("after-reconnect", "ou_authorized", "p2p", "hello again"));
            assertEquals(2, accepted.size());
            verify(agents, times(1)).newConversation("a", "Feishu");
            verify(channel, times(2)).connect();
            assertEquals(1, registrations.get());
        }
    }

    @Test
    void expiredAuthorizationClearsTheUnusableQrCode() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            options.getOnQRCode()
                                    .accept(new QRCodeInfo("https://open.feishu.cn/expired", 1));
                            throw new RegisterAppException(
                                    "expired_token", "Authorization expired");
                        })) {
            service.register("a");
            long deadline = System.currentTimeMillis() + 3000;
            while (!"ERROR".equals(service.status("a").get("status"))
                    && System.currentTimeMillis() < deadline) Thread.sleep(10);
            Map<String, Object> status = service.status("a");
            assertEquals("ERROR", status.get("status"));
            assertTrue(status.get("error").toString().contains("expired_token"));
            assertNull(status.get("authorizationUrl"));
            assertEquals(0L, status.get("expiresAt"));
            assertEquals(false, status.get("configured"));
            verify(channel, never()).connect();
        }
    }

    @Test
    void disconnectedConfigurationStaysDisconnectedAfterRestart() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            throw new AssertionError("No registration expected");
                        })) {
            saved =
                    "{\"appId\":\"cli_saved\",\"appSecret\":\"secret\",\"ownerId\":\"ou_owner\",\"sessionId\":\"conversation\",\"enabled\":false}";
            service.start();
            assertEquals("DISCONNECTED", service.status("a").get("status"));
            assertEquals(true, service.status("a").get("configured"));
            assertEquals("cli_saved", service.status("a").get("appId"));
            verify(channel, never()).connect();
            assertEquals("CONNECTED", service.reconnect("a").get("status"));
            verify(agents, never()).newConversation(anyString(), anyString());
        }
    }

    @Test
    void legacyConfigurationRestoresAndFailedReconnectKeepsCredentials() throws Exception {
        try (FeishuService service =
                fixture(
                        options -> {
                            throw new AssertionError("No registration expected");
                        })) {
            saved =
                    "{\"appId\":\"cli_saved\",\"appSecret\":\"secret\",\"ownerId\":\"ou_owner\",\"sessionId\":\"conversation\"}";
            service.start();
            assertEquals("CONNECTED", service.status("a").get("status"));
            CompletableFuture<BotIdentity> failure = new CompletableFuture<>();
            failure.completeExceptionally(new IllegalStateException("network unavailable"));
            when(channel.connect()).thenReturn(failure);
            assertEquals("ERROR", service.reconnect("a").get("status"));
            assertEquals("cli_saved", json.readTree(saved).path("appId").asText());
            assertEquals("ou_owner", json.readTree(saved).path("ownerId").asText());
            when(channel.connect()).thenReturn(CompletableFuture.completedFuture(null));
            assertEquals("CONNECTED", service.reconnect("a").get("status"));
            handlers.get("message").handle(message("after-retry", "ou_owner", "p2p", "hello"));
            assertEquals(1, accepted.size());
            verify(agents, never()).newConversation(anyString(), anyString());
        }
    }
}
