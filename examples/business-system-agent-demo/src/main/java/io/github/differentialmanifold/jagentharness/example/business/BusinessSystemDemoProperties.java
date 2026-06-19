package io.github.differentialmanifold.jagentharness.example.business;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-demo")
public class BusinessSystemDemoProperties {

    private String workspacePath = "examples/business-system-agent-demo";
    private String skillsSource = "examples/business-system-agent-demo/skills";

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public String getSkillsSource() {
        return skillsSource;
    }

    public void setSkillsSource(String skillsSource) {
        this.skillsSource = skillsSource;
    }
}
