package dev.gatekeeper.observability;

import dev.gatekeeper.persistence.JpaScanLog;
import dev.gatekeeper.scanlog.ScanLog;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes the metered wrapper the {@link ScanLog} everything else receives.
 * {@link JpaScanLog} is still a bean in its own right; the controllers just
 * never see it directly.
 */
@Configuration
class ObservabilityConfig {

    @Bean
    @Primary
    ScanLog meteredScanLog(JpaScanLog jpaScanLog, MeterRegistry registry) {
        return new MeteredScanLog(jpaScanLog, registry);
    }
}
