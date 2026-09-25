package dev.gatekeeper.persistence;

import dev.gatekeeper.gate.ScanRecord;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not {@code @DataJpaTest}: that wraps the whole test in one transaction on one
 * connection, which cannot express two requests racing. This uses the full
 * context so each thread gets its own connection and its own committed
 * transaction, as two real HTTP requests would.
 */
@SpringBootTest
@Import(JpaScanLogConcurrencyTest.PauseBeforeWriting.class)
class JpaScanLogConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-26T18:00:00Z");

    /**
     * Wraps the repository so a test can hold every caller at the moment just
     * before it first writes. A plain JDK proxy rather than a Mockito spy:
     * Spring Data repositories are themselves interface proxies, which Mockito
     * cannot call through to.
     */
    @TestConfiguration
    static class PauseBeforeWriting {

        /** When set, each write waits here until this many callers have arrived. */
        static final AtomicReference<CountDownLatch> RENDEZVOUS = new AtomicReference<>();

        @Bean
        static BeanPostProcessor repositoryWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ScanRecordRepository real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            ScanRecordRepository.class.getClassLoader(),
                            new Class<?>[]{ScanRecordRepository.class},
                            (proxy, method, args) -> {
                                boolean writes = method.getName().equals("save") || method.getName().equals("saveAll");
                                CountDownLatch latch = RENDEZVOUS.get();
                                if (writes && latch != null) {
                                    latch.countDown();
                                    latch.await(10, TimeUnit.SECONDS);
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }
    }

    @Autowired
    private JpaScanLog scanLog; // the concrete store; the injected ScanLog bean is its metrics wrapper

    @Autowired
    private MeterRegistry registry;

    /**
     * The interleaving that used to fail, forced rather than hoped for. Two
     * uploads carry the same scans (a gate's retry arriving while the original
     * is still being processed). Each is held right before its first write,
     * which is after each has already looked and found nothing stored, so both
     * then write the same rows. Before the fix the second one hit the unique
     * constraint and its request failed with a 500.
     */
    @Test
    void twoUploadsOfTheSameScansAtTheSameMomentBothSucceedAndEachScanIsStoredOnce() throws Exception {
        List<ScanRecord> scans = List.of(
                new ScanRecord("gate-race", "t1", T0, true),
                new ScanRecord("gate-race", "t2", T0, true),
                new ScanRecord("gate-race", "t3", T0, false));

        PauseBeforeWriting.RENDEZVOUS.set(new CountDownLatch(2));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> scanLog.store(scans));
            Future<Integer> second = pool.submit(() -> scanLog.store(scans));

            int storedByFirst = first.get(60, TimeUnit.SECONDS);   // throws here if a request failed
            int storedBySecond = second.get(60, TimeUnit.SECONDS);

            assertThat(storedByFirst + storedBySecond)
                    .as("between them, each of the 3 scans was newly stored exactly once")
                    .isEqualTo(3);
            assertThat(scanLog.totalStoredScans()).isEqualTo(3);
            assertThat(registry.get("gatekeeper.store.conflict.retries").counter().count())
                    .as("the collision was real, and was resolved by a retry rather than avoided")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            PauseBeforeWriting.RENDEZVOUS.set(null);
            pool.shutdownNow();
        }
    }
}
