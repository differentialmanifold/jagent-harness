package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import org.springframework.beans.factory.ObjectProvider;

public final class HistoryTool extends PersonalTool {
    private final PersonalAgentKnowledge knowledge;

    HistoryTool(
            ObjectProvider<PersonalAgentService> s,
            PersonalAgentStore store,
            ObjectMapper json,
            PersonalAgentKnowledge k) {
        super(
                "history",
                "Recall evidence missing from current context, including older conversations and your projects. Searches stored message text, not Runner files or other users' work.",
                s,
                store,
                json);
        knowledge = k;
        action(
                "search",
                "Literal phrase search, including Chinese; returns bounded excerpts with IDs. Shorten the query if needed.",
                fields(
                        "query",
                        text("Literal phrase."),
                        "scope",
                        choice("Default all.", "all", "personal", "projects"),
                        "sessionId",
                        text("Optional owned session filter."),
                        "limit",
                        number("Maximum matches; default 10, at most 20.")),
                new String[] {"history_search"},
                "query");
        action(
                "read",
                "Read nearby evidence around a search result.",
                fields(
                        "sessionId",
                        text("Owned session."),
                        "messageId",
                        text("Message ID from search.")),
                new String[] {"history_around"},
                "sessionId",
                "messageId");
    }

    Object run(String id, ToolContext c, String action, JsonNode args) {
        if ("search".equals(action))
            return knowledge.search(
                    id,
                    required(args, "query"),
                    args.path("scope").asText("all"),
                    args.path("sessionId").asText(null),
                    args.path("limit").asInt(10));
        return knowledge.around(id, required(args, "sessionId"), required(args, "messageId"));
    }
}
