package com.example.serverdemo;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** An ordinary consumer application. Framework components come from the Maven dependency. */
@SpringBootApplication
public class ServerDemoApplication {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource pool =
                properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if (pool.getJdbcUrl().startsWith("jdbc:sqlite:"))
            pool.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return pool;
    }

    public static void main(String[] args) {
        if (args.length > 0 && "users".equals(args[0])) {
            UserCommand.run(args);
            return;
        }
        SpringApplication.run(ServerDemoApplication.class, args);
    }
}
