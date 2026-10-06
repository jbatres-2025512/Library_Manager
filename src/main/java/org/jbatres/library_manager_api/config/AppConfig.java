package org.jbatres.library_manager_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** Single time source for the whole app (JWT, loan dates) so time can be controlled in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}