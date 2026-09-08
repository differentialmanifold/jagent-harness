package com.example.serverdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** An ordinary consumer application. Framework components come from the Maven dependency. */
@SpringBootApplication
public class ServerDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerDemoApplication.class, args);
    }
}
