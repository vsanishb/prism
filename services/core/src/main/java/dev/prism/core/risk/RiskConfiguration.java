package dev.prism.core.risk;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads the scoring weights at startup, so that a broken configuration stops the service at once. */
@Configuration
class RiskConfiguration {

    @Bean
    RiskScorer riskScorer() {
        return new RiskScorer(ScoringConfig.load());
    }
}
