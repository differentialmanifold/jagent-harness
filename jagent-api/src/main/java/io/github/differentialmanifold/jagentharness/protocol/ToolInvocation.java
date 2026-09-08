package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ToolInvocation {
    public String toolCallId;
    public String name;
    public String turnId;
    public Map<String, Object> arguments = new LinkedHashMap<>();
}
