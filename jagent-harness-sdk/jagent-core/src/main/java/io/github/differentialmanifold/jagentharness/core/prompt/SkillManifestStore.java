package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;

public interface SkillManifestStore {

    List<SkillManifest> listManifests();

    default List<SkillManifest> listManifests(KnowledgeScope scope) {
        return scope == null || scope.isGlobal()
                ? listManifests()
                : java.util.Collections.<SkillManifest>emptyList();
    }
}
