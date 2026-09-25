package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delivers simulated uploads to a running server over HTTP, the way real
 * gates would: several at once, in batches no bigger than the API accepts,
 * and retrying a request that fails. Retrying is not a convenience here: it
 * is exactly what the sync protocol's idempotence exists to make safe, so a
 * failed-and-retried request is counted rather than hidden.
 *
 * <p>Plain JDK only (no JSON library, no Spring), so it runs from a bare
 * {@code java -cp target/classes} with nothing else on the classpath. The
 * request format is simple and fully under this class's control; responses
 * are read with regular expressions, which is fine for the small, flat
 * objects the server returns and would not be for anything richer.
 */
final class HttpSyncRunner {

    /** What one delivery run experienced. */
    record Outcome(
            List<Long> successLatenciesNanos,
            int failedAttempts,
            Map<Integer, Integer> failuresByStatus,
            int permanentFailures,
            long elapsedNanos) {

        int successfulRequests() {
            return successLatenciesNanos.size();
        }
    }

    /** The totals the server reports from {@code GET /reconciliation/summary}. */
    record Summary(int totalScansConsidered, int acceptedTickets, int conflictCount) {
    }

    private record Batch(String gateId, List<ScanRecord> records) {
    }

    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+)");

    private final HttpClient client;
    private final URI base;
    private final int concurrency;
    private final int batchSize;
    private final int maxAttempts;

    HttpSyncRunner(URI base, int concurrency, int batchSize, int maxAttempts) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.base = base;
        this.concurrency = concurrency;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    Outcome run(List<SyncEvent> uploads) throws InterruptedException {
        List<Batch> batches = new ArrayList<>();
        for (SyncEvent upload : uploads) {
            List<ScanRecord> records = upload.records();
            for (int from = 0; from < records.size(); from += batchSize) {
                int to = Math.min(from + batchSize, records.size());
                batches.add(new Batch(upload.gateId(), records.subList(from, to)));
            }
        }

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        ConcurrentHashMap<Integer, Integer> failures = new ConcurrentHashMap<>();
        AtomicInteger failedAttempts = new AtomicInteger();
        AtomicInteger permanentFailures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.nanoTime();
        for (Batch batch : batches) {
            pool.submit(() -> {
                if (!deliver(batch, latencies, failures, failedAttempts)) {
                    permanentFailures.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        long elapsed = System.nanoTime() - start;

        return new Outcome(new ArrayList<>(latencies), failedAttempts.get(), Map.copyOf(failures),
                permanentFailures.get(), elapsed);
    }

    /** Sends one batch, retrying on failure. Returns whether it eventually got through. */
    private boolean deliver(Batch batch, ConcurrentLinkedQueue<Long> latencies,
                            ConcurrentHashMap<Integer, Integer> failures, AtomicInteger failedAttempts) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/gates/" + batch.gateId() + "/sync"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(batch.records())))
                .build();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started = System.nanoTime();
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    latencies.add(System.nanoTime() - started);
                    return true;
                }
                failedAttempts.incrementAndGet();
                failures.merge(response.statusCode(), 1, Integer::sum);
            } catch (IOException e) {
                failedAttempts.incrementAndGet();
                failures.merge(0, 1, Integer::sum); // 0: no HTTP response at all
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(50L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    Summary fetchSummary() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/reconciliation/summary"))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET /reconciliation/summary returned " + response.statusCode());
        }
        return parseSummary(response.body());
    }

    static Summary parseSummary(String json) {
        Map<String, Integer> fields = new ConcurrentHashMap<>();
        Matcher matcher = NUMBER_FIELD.matcher(json);
        while (matcher.find()) {
            fields.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        for (String required : List.of("totalScansConsidered", "acceptedTickets", "conflictCount")) {
            if (!fields.containsKey(required)) {
                throw new IllegalStateException("summary response has no " + required + ": " + json);
            }
        }
        return new Summary(fields.get("totalScansConsidered"), fields.get("acceptedTickets"), fields.get("conflictCount"));
    }

    static String toJson(List<ScanRecord> records) {
        StringBuilder json = new StringBuilder(records.size() * 100 + 16).append("{\"scans\":[");
        for (int i = 0; i < records.size(); i++) {
            ScanRecord record = records.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"ticketId\":\"").append(escape(record.ticketId()))
                    .append("\",\"scannedAt\":\"").append(record.scannedAt())
                    .append("\",\"accepted\":").append(record.accepted()).append('}');
        }
        return json.append("]}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
