package dev.healstore.coordinator;

import dev.healstore.common.web.WebSupportConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(WebSupportConfiguration.class)
public class CoordinatorApplication {
    public static void main(String[] args) { SpringApplication.run(CoordinatorApplication.class, args); }
}
