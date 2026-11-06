package dev.prism.core.platform.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Ends the seed run once every seeder has finished. */
@Component
@Profile("seed")
@Order(Ordered.LOWEST_PRECEDENCE)
class SeedCompletion implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    SeedCompletion(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        context.close();
    }
}
