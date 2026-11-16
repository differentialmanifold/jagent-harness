package com.example.serverdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** An ordinary consumer application. Framework components come from the Maven dependency. */
@SpringBootApplication
public class ServerDemoApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "users".equals(args[0])) {
            UserCommand.run(args);
            return;
        }
        SpringApplication.run(ServerDemoApplication.class, args);
    }
}
