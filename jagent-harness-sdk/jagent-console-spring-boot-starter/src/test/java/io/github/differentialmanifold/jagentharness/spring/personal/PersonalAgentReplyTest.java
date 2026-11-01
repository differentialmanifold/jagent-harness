package io.github.differentialmanifold.jagentharness.spring.personal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PersonalAgentReplyTest {
    @Test
    void acceptsOrdinaryRepliesAndPreservesCodeExamples() {
        for (String reply :
                new String[] {
                    "已完成测试。",
                    "## Result\n- **Passed**",
                    "```json\n{\"result\":true}\n```",
                    "{\"userReply\":null} extra",
                    "null"
                }) {
            assertEquals(reply, PersonalAgentReply.decode(reply, false));
            assertEquals(reply, PersonalAgentReply.decode(reply, true));
        }
        String longReply = String.join("", java.util.Collections.nCopies(33000, "a"));
        assertEquals(longReply, PersonalAgentReply.decode(longReply, false));
    }

    @Test
    void acceptsExistingEnvelopesIncludingMarkdownFencesAndExtraMetadata() {
        assertEquals("Done", PersonalAgentReply.decode("{\"userReply\":\"Done\"}", false));
        assertEquals(
                "Done",
                PersonalAgentReply.decode(
                        "```json\n{\"userReply\":\"Done\",\"status\":\"complete\"}\n```", false));
    }

    @Test
    void onlyExplicitBackgroundNullIsSilent() {
        assertEquals("", PersonalAgentReply.decode("{\"userReply\":null}", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalAgentReply.decode("{\"userReply\":null}", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalAgentReply.decode("{\"userReply\":true}", true));
        assertThrows(IllegalArgumentException.class, () -> PersonalAgentReply.decode("", true));
        assertThrows(IllegalArgumentException.class, () -> PersonalAgentReply.decode(null, false));
    }
}
