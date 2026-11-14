package io.github.differentialmanifold.jagentharness.core.model;

import java.util.*;

public interface ModelCatalogStore {
    class Configuration {
        public long revision;

        @com.fasterxml.jackson.annotation.JsonProperty(
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String source;

        public String defaultModelId;
        public List<ModelProfile> models = new ArrayList<>();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    class Selection {
        public ModelProfile model;
    }

    Configuration configuration();

    boolean save(Configuration configuration);

    Selection selection(String session, String run);

    void select(String session, String run, Selection selection);
}
