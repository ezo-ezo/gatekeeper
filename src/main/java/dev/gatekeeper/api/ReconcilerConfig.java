package dev.gatekeeper.api;

import dev.gatekeeper.reconcile.Reconciler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the single, process-wide {@link Reconciler} that every gate syncs
 * into. {@code Reconciler} itself stays a plain, framework-free class so it
 * can be used and tested with no Spring context at all; this is the only
 * place that knows it lives in a Spring app.
 */
@Configuration
class ReconcilerConfig {

    @Bean
    Reconciler reconciler() {
        return new Reconciler();
    }
}
