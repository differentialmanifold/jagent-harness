package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.channel.*;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.*;
import com.lark.oapi.scene.registration.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.spring.web.RunnerService;
import java.util.*;
import java.util.concurrent.*;

/** Optional Feishu DM channel. Credentials never enter tools, prompts, or public responses. */
public class FeishuService implements AutoCloseable {
    public static class Configuration {
        public String appId, appSecret, ownerId, sessionId;
        public String brand = "feishu";
        public boolean enabled = true;
    }

    private static class Connection {
        volatile String status = "CONNECTING", error = "", url, pairCode;
        volatile long expiresAt;
        volatile LarkChannel channel;
        volatile Configuration configuration;
        Future<?> registration;
    }

    interface Registrar {
        RegisterAppResult register(RegisterAppOptions options) throws Exception;
    }

    interface ChannelFactory {
        LarkChannel create(LarkChannelOptions options);
    }

    private final Registrar registrar;
    private final ChannelFactory channelFactory;
    private final PersonalAgentService agents;
    private final PersonalAgentStore store;
    private final ObjectMapper json;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService setups =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "feishu-setup");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService delivery =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "feishu-delivery");
                        t.setDaemon(true);
                        return t;
                    });

    public FeishuService(PersonalAgentService agents, PersonalAgentStore store, ObjectMapper json) {
        this(agents, store, json, RegisterApp::register, LarkChannelFactory::createLarkChannel);
    }

    FeishuService(
            PersonalAgentService agents,
            PersonalAgentStore store,
            ObjectMapper json,
            Registrar registrar,
            ChannelFactory channelFactory) {
        this.agents = agents;
        this.store = store;
        this.json = json;
        this.registrar = registrar;
        this.channelFactory = channelFactory;
    }

    public void start() {
        for (Profile p : store.profiles()) {
            String saved = store.channel(p.id);
            if (saved != null)
                try {
                    Configuration config = json.readValue(saved, Configuration.class);
                    if (config.enabled) connect(p.id, config);
                } catch (Exception e) {
                    Connection c = new Connection();
                    c.status = "ERROR";
                    c.error = "Stored connection could not be restored. Reconnect in the console.";
                    connections.put(p.id, c);
                }
        }
        delivery.scheduleWithFixedDelay(this::deliver, 2, 2, TimeUnit.SECONDS);
    }

    public synchronized Map<String, Object> register(String agent) {
        agents.require(agent);
        disconnect(agent);
        Connection c = new Connection();
        c.status = "REGISTERING";
        connections.put(agent, c);
        c.registration =
                setups.submit(
                        () -> {
                            try {
                                RegisterAppResult result =
                                        registrar.register(
                                                RegisterAppOptions.newBuilder()
                                                        .source("jagent-harness")
                                                        .appPreset(
                                                                AppPreset.newBuilder()
                                                                        .name(
                                                                                agents.require(
                                                                                                agent)
                                                                                        .name)
                                                                        .desc(
                                                                                "Personal Agent powered by JAgentHarness")
                                                                        .build())
                                                        .addons(
                                                                AppAddons.newBuilder()
                                                                        .preset(false)
                                                                        .tenantScopes(
                                                                                "im:message:send_as_bot",
                                                                                "im:message.p2p_msg:readonly")
                                                                        .tenantEvents(
                                                                                "im.message.receive_v1")
                                                                        .build())
                                                        .onQRCode(
                                                                info -> {
                                                                    c.url = info.getUrl();
                                                                    c.expiresAt =
                                                                            System
                                                                                            .currentTimeMillis()
                                                                                    + info
                                                                                                    .getExpireIn()
                                                                                            * 1000L;
                                                                    c.status =
                                                                            "AWAITING_AUTHORIZATION";
                                                                })
                                                        .build());
                                Configuration config = new Configuration();
                                config.appId = result.getClientId();
                                config.appSecret = result.getClientSecret();
                                if (result.getUserInfo() != null) {
                                    config.ownerId = result.getUserInfo().getOpenId();
                                    config.brand = result.getUserInfo().getTenantBrand();
                                }
                                synchronized (this) {
                                    if (connections.get(agent) != c) return;
                                    persistAndConnect(agent, c, config);
                                }
                            } catch (Exception e) {
                                if (connections.get(agent) == c) {
                                    c.status = "ERROR";
                                    c.url = null;
                                    c.expiresAt = 0;
                                    c.error =
                                            e instanceof RegisterAppException
                                                    ? "Authorization ended: "
                                                            + ((RegisterAppException) e).getCode()
                                                            + ". Try again or connect an existing app."
                                                    : "Registration could not complete. Check network access and try again.";
                                }
                            }
                        });
        return status(agent);
    }

    public synchronized Map<String, Object> connect(String agent, Configuration config) {
        agents.require(agent);
        PersonalAgentService.text(config.appId, "appId", 256);
        PersonalAgentService.text(config.appSecret, "appSecret", 512);
        if (!Arrays.asList("feishu", "lark").contains(config.brand))
            throw new IllegalArgumentException("Choose Feishu or Lark");
        closeConnection(agent);
        config.enabled = true;
        Connection c = new Connection();
        connections.put(agent, c);
        persistAndConnect(agent, c, config);
        return status(agent);
    }

    private void persistAndConnect(String agent, Connection c, Configuration config) {
        if (config.sessionId == null || store.conversation(config.sessionId) == null)
            config.sessionId = agents.newConversation(agent, "Feishu").sessionId;
        agents.conversation(agent, config.sessionId);
        c.configuration = config;
        c.url = null;
        if (config.ownerId == null || config.ownerId.isEmpty()) {
            c.pairCode = "pair " + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            c.expiresAt = System.currentTimeMillis() + 600000;
        }
        persist(agent, config);
        LarkChannel channel =
                channelFactory.create(
                        LarkChannelOptions.newBuilder(config.appId, config.appSecret)
                                .transport("websocket")
                                .source("jagent-harness")
                                .domain(
                                        "lark".equals(config.brand)
                                                ? "https://open.larksuite.com"
                                                : "https://open.feishu.cn")
                                .build());
        c.channel = channel;
        c.status = "CONNECTING";
        channel.on("message", (ChannelEventHandler<NormalizedMessage>) m -> receive(agent, c, m));
        channel.on(
                "reconnecting",
                event -> {
                    if (connections.get(agent) == c) c.status = "RECONNECTING";
                });
        channel.on(
                "reconnected",
                event -> {
                    if (connections.get(agent) == c)
                        c.status = config.ownerId == null ? "PAIRING" : "CONNECTED";
                });
        channel.on(
                "error",
                event -> {
                    c.error =
                            "Feishu reported a channel error. Check bot permissions and connection settings.";
                });
        channel.connect()
                .whenComplete(
                        (bot, error) -> {
                            if (connections.get(agent) != c) {
                                channel.disconnect();
                                return;
                            }
                            if (error == null) {
                                c.status = config.ownerId == null ? "PAIRING" : "CONNECTED";
                                c.error = "";
                            } else {
                                c.status = "ERROR";
                                c.error =
                                        "Could not connect. For an existing app, enable the bot, receive-message event, long connection, and send/receive DM permissions, then publish the app. Check server network access.";
                            }
                        });
    }

    private synchronized void receive(String agent, Connection c, NormalizedMessage m) {
        if (connections.get(agent) != c || !"p2p".equals(m.getChatType()) || m.getContent() == null)
            return;
        Configuration config = c.configuration;
        if (config.ownerId == null || config.ownerId.isEmpty()) {
            if (c.pairCode == null
                    || System.currentTimeMillis() > c.expiresAt
                    || !c.pairCode.equals(m.getContent().trim())) return;
            config.ownerId = m.getSenderId();
            c.pairCode = null;
            c.status = "CONNECTED";
            persist(agent, config);
            return;
        }
        if (!config.ownerId.equals(m.getSenderId()) || !agents.require(agent).enabled) return;
        // This first channel supports text and rich-text converted by the official SDK.
        if (!Arrays.asList("text", "post").contains(m.getRawContentType())
                || m.getContent() == null
                || m.getContent().trim().isEmpty()) return;
        synchronized (agents) {
            if (config.sessionId == null || store.conversation(config.sessionId) == null) {
                config.sessionId = agents.newConversation(agent, "Feishu").sessionId;
                persist(agent, config);
            }
            Inbox input = new Inbox();
            input.id = "feishu_" + RunnerService.hash(config.appId + ":" + m.getMessageId());
            input.agentId = agent;
            input.sessionId = config.sessionId;
            input.source = "FEISHU";
            input.senderId = m.getSenderId();
            input.replyTo = new ReplyTarget("feishu", config.appId, m.getSenderId());
            input.text = PersonalAgentService.bounded(m.getContent(), 32000);
            input.createdAt = System.currentTimeMillis();
            store.enqueue(input);
        }
    }

    private void persist(String agent, Configuration config) {
        try {
            store.channel(agent, json.writeValueAsString(config));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save channel configuration");
        }
    }

    public Map<String, Object> status(String agent) {
        agents.require(agent);
        Connection c = connections.get(agent);
        Configuration config = c == null ? null : c.configuration;
        String saved = store.channel(agent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("configured", saved != null);
        out.put("status", c == null ? "DISCONNECTED" : c.status);
        if (c != null) {
            out.put("error", c.error);
            out.put("authorizationUrl", c.url);
            out.put("expiresAt", c.expiresAt);
            out.put("pairCode", c.pairCode);
        }
        if (config == null && saved != null) {
            try {
                config = json.readValue(saved, Configuration.class);
            } catch (java.io.IOException e) {
                out.put("error", "Saved connection could not be read. Connect the app again.");
            }
        }
        if (config != null) {
            out.put("appId", config.appId);
            out.put(
                    "sessionId",
                    config.sessionId != null && store.conversation(config.sessionId) != null
                            ? config.sessionId
                            : null);
            out.put("paired", config.ownerId != null && !config.ownerId.isEmpty());
        }
        return out;
    }

    public synchronized Map<String, Object> reconnect(String agent) {
        agents.require(agent);
        String saved = store.channel(agent);
        if (saved == null) throw new IllegalArgumentException("Connect a Feishu app first");
        try {
            return connect(agent, json.readValue(saved, Configuration.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Saved connection could not be read. Connect the app again.");
        }
    }

    public synchronized void disconnect(String agent) {
        agents.require(agent);
        String saved = store.channel(agent);
        if (saved != null) {
            try {
                Configuration config = json.readValue(saved, Configuration.class);
                config.enabled = false;
                persist(agent, config);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Saved connection could not be read.");
            }
        }
        closeConnection(agent);
    }

    private void closeConnection(String agent) {
        Connection old = connections.remove(agent);
        if (old != null) {
            if (old.registration != null) old.registration.cancel(true);
            if (old.channel != null) old.channel.disconnect();
        }
    }

    void deliver() {
        try {
            List<Inbox> pending = new ArrayList<>(store.inbox("DONE"));
            pending.addAll(store.inbox("FAILED"));
            for (Inbox m : pending) {
                if (m.delivered
                        || m.replyTo == null
                        || !"feishu".equals(m.replyTo.channel)
                        || "LEARNING".equals(m.source)
                        || m.deliveryAttempts >= 5
                        || m.nextDeliveryAt > System.currentTimeMillis()) continue;
                Connection c = connections.get(m.agentId);
                if (c == null || !"CONNECTED".equals(c.status) || c.configuration.ownerId == null)
                    continue;
                if (!c.configuration.ownerId.equals(m.replyTo.recipientId)
                        || !Objects.equals(c.configuration.appId, m.replyTo.accountId)) {
                    m.delivered = true;
                    m.deliveryError =
                            "The linked Feishu account changed. This reply is available in the console.";
                    store.update(m, m.status);
                    continue;
                }
                try {
                    String text =
                            m.answer + (m.error == null || m.error.isEmpty() ? "" : "\n" + m.error);
                    if (!text.trim().isEmpty())
                        c.channel
                                .send(m.replyTo.recipientId, SendInput.text(text))
                                .get(30, TimeUnit.SECONDS);
                    m.delivered = true;
                    m.deliveryError = null;
                } catch (Exception e) {
                    m.deliveryAttempts++;
                    m.nextDeliveryAt =
                            System.currentTimeMillis()
                                    + Math.min(300000, 5000L * (1L << m.deliveryAttempts));
                    m.deliveryError =
                            "Feishu delivery failed. Check connection and permissions; the response is retained here.";
                }
                store.update(m, m.status);
            }
        } catch (Exception ignored) {
            /* Keep persisted responses pending while the database is unavailable. */
        }
    }

    public void retry(String agent, String id) {
        Inbox m = store.message(id);
        if (m == null || !agent.equals(m.agentId))
            throw new IllegalArgumentException("Message not found");
        m.deliveryAttempts = 0;
        m.nextDeliveryAt = 0;
        m.deliveryError = null;
        store.update(m, m.status);
    }

    public synchronized void close() {
        for (Connection c : connections.values()) {
            if (c.registration != null) c.registration.cancel(true);
            if (c.channel != null) c.channel.disconnect();
        }
        connections.clear();
        setups.shutdownNow();
        delivery.shutdownNow();
    }
}
