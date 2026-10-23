package dev.healstore.node;

import dev.healstore.common.web.WebSupportConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@Import(WebSupportConfiguration.class)
public class StorageNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(StorageNodeApplication.class, args);
    }
}
