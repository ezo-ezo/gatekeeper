package dev.gatekeeper.persistence;

import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.reconcile.ReconciliationReport;
import dev.gatekeeper.reconcile.Reconciler;
import dev.gatekeeper.scanlog.ScanLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ScanLog} backed by a real database, so a gate's sync history
 * survives a server restart. {@code Reconciler}'s own contract is untouched
 * by adding this: on every read, every persisted scan is loaded and fed into
 * a brand new {@link Reconciler}, relying entirely on
 * {@link Reconciler#reconcile()} already being a pure function of whatever
 * has been ingested (see {@code docs/design.md}). Reconciler's own tests
 * did not need to change for this to work.
 */
@Service
public class JpaScanLog implements ScanLog {

    /** Tries per batch when concurrent uploads keep getting to the same scans first. */
    private static final int MAX_ATTEMPTS = 5;

    /** Tickets per lookup query, to stay far below any database's limit on parameters. */
    private static final int LOOKUP_CHUNK = 500;

    private final ScanRecordRepository repository;
    private final TransactionTemplate transactions;
    private final Counter conflictRetries;

    public JpaScanLog(ScanRecordRepository repository, PlatformTransactionManager transactionManager,
                      MeterRegistry registry) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.conflictRetries = Counter.builder("gatekeeper.store.conflict.retries")
                .description("Batches re-run because a concurrent upload stored some of the same scans first")
                .register(registry);
    }

    /**
     * Stores the scans not already present and returns how many were new.
     *
     * <p>Two uploads can carry the same scans at the same moment: a gate
     * retrying after a timeout while its first attempt is still being
     * processed. Each looks, finds nothing stored, and both insert. The
     * database's unique constraint stops the duplicate (so nothing wrong is
     * ever stored) but fails the second transaction. That is not an error to
     * report, it is the losing side of a race, so the whole batch is simply
     * run again in a fresh transaction: by then the rows the winner committed
     * are visible, get skipped, and the batch completes. A batch is all or
     * nothing, so retrying it cannot half-apply anything.
     *
     * <p>The retry needs {@code store} to begin its own transaction, as the
     * HTTP layer's calls do. Called from inside a caller's transaction, a
     * conflict poisons that transaction and can only be propagated.
     */
    @Override
    public int store(Collection<ScanRecord> scans) {
        for (int attempt = 1; ; attempt++) {
            try {
                Integer stored = transactions.execute(status -> storeInOneTransaction(scans));
                return stored == null ? 0 : stored;
            } catch (DataIntegrityViolationException | ConcurrencyFailureException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    throw conflict;
                }
                conflictRetries.increment();
                pause(attempt);
            }
        }
    }

    private int storeInOneTransaction(Collection<ScanRecord> scans) {
        Set<ScanRecord> known = findAlreadyStored(scans);

        List<ScanRecordEntity> toInsert = new ArrayList<>();
        for (ScanRecord scan : scans) {
            // known doubles as the "seen in this batch" set, so a scan listed twice in one
            // request is inserted once.
            if (known.add(scan)) {
                toInsert.add(ScanRecordEntity.fromDomain(scan));
            }
        }
        repository.saveAll(toInsert);
        return toInsert.size();
    }

    /** Which of these scans are already stored: one query per gate (per chunk of tickets), not one per scan. */
    private Set<ScanRecord> findAlreadyStored(Collection<ScanRecord> scans) {
        Map<String, Set<String>> ticketsByGate = new LinkedHashMap<>();
        for (ScanRecord scan : scans) {
            ticketsByGate.computeIfAbsent(scan.gateId(), gate -> new LinkedHashSet<>()).add(scan.ticketId());
        }

        Set<ScanRecord> stored = new HashSet<>();
        ticketsByGate.forEach((gateId, ticketIds) -> {
            List<String> ids = new ArrayList<>(ticketIds);
            for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(from + LOOKUP_CHUNK, ids.size()));
                repository.findByGateIdAndTicketIdIn(gateId, chunk)
                        .forEach(entity -> stored.add(entity.toDomain()));
            }
        });
        return stored;
    }

    /** A short, growing pause so two requests that just collided do not retry in lockstep. */
    private static void pause(int attempt) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<ScanRecord> all = repository.findAll().stream().map(ScanRecordEntity::toDomain).toList();
        Reconciler reconciler = new Reconciler();
        reconciler.ingest(all);
        return reconciler.reconcile();
    }

    @Override
    @Transactional(readOnly = true)
    public long totalStoredScans() {
        return repository.count();
    }
}
