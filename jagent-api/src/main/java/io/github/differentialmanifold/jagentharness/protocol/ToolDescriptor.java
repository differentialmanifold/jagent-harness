package io.github.differentialmanifold.jagentharness.protocol;

import java.util.*;

/** JSON wire contract. Independent of any framework and JSON library. */
public class ToolDescriptor {
    public String name;
    public String description;
    public Map<String, Object> parameters = new LinkedHashMap<>();
}
