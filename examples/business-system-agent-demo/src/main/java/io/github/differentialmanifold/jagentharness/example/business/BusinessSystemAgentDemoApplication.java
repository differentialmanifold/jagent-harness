package io.github.differentialmanifold.jagentharness.example.business;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BusinessSystemDemoProperties.class)
public class BusinessSystemAgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessSystemAgentDemoApplication.class, args);
    }
}
