package io.github.differentialmanifold.jagentharness.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class RunnerBuildTest {
    @Test
    void metadataMatchesTheBuiltArtifactAndSuppliedRevision() throws Exception {
        org.w3c.dom.Document pom =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new File(System.getProperty("basedir", "."), "pom.xml"));
        String version =
                XPathFactory.newInstance().newXPath().evaluate("/project/parent/version", pom);
        RunnerBuild build = new RunnerBuild();
        assertEquals(version, build.value("version"));
        String revision = System.getProperty("harness.source.revision", "unknown");
        assertEquals("unknown".equals(revision) ? null : revision, build.value("buildRevision"));
        assertNull(build.value("missing"));
    }
}
