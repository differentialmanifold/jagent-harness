package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.util.function.Supplier;

public final class SkillsTool extends PersonalTool {
    private final PersonalAgentKnowledge knowledge;

    SkillsTool(
            Supplier<PersonalAgentService> s,
            PersonalAgentStore store,
            ObjectMapper json,
            PersonalAgentKnowledge k) {
        super(
                "skills",
                "Maintain your private reusable methods. Use skill to load a method for use; use skills to edit it. Methods do not install tools or grant permissions.",
                s,
                store,
                json);
        knowledge = k;
        action("list", "List your private skill files.", fields(), new String[] {"skills_list"});
        action(
                "read",
                "Read a private file and shared profile revision before editing. New files have null content.",
                fields("path", text("Path under skills/<name>/.")),
                new String[] {"skills_read"},
                "path");
        action(
                "save",
                "Create or replace a verified method or resource, up to 40,000 characters. SKILL.md needs YAML name matching its directory and description. Read first.",
                editFields(
                        "path",
                        text(
                                "skills/<name>/SKILL.md or references/, scripts/, templates/, assets/ resource."),
                        "content",
                        text("Complete UTF-8 content, grounded in verified evidence.")),
                new String[] {"skills_save"},
                "path",
                "content",
                "reason",
                "revision");
        action(
                "remove",
                "Remove a private Skill from discovery by deleting its SKILL.md. Resources and change history remain for undo.",
                editFields("path", text("skills/<name>/SKILL.md.")),
                new String[] {"skills_archive"},
                "path",
                "reason",
                "revision");
    }

    Object run(String id, ToolContext c, String action, JsonNode args) {
        if ("list".equals(action)) return knowledge.skills(id);
        if ("read".equals(action)) return knowledge.skill(id, required(args, "path"));
        return knowledge.edit(id, "skills." + action, args, c.getSessionId(), "AGENT");
    }
}
