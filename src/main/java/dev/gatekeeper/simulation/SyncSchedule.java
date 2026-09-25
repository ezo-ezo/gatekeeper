package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Decides how the gates' logs reach the server once connectivity returns,
 * and it is designed to be as awkward as reality is:
 *
 * <ul>
 *   <li>A gate uploads in several batches, and each batch is its log <em>so far</em>,
 *       so batches overlap: everything in the first is sent again in the second.</li>
 *   <li>Some uploads are sent twice, as after a timeout where the gate cannot tell
 *       whether the first attempt landed.</li>
 *   <li>Every gate's uploads are shuffled together, so a later batch can arrive before an
 *       earlier one and gates interleave arbitrarily.</li>
 * </ul>
 *
 * <p>The last batch of every gate always contains its whole log, so once every
 * upload has arrived the server has seen everything exactly as it happened.
 */
public final class SyncSchedule {

    private SyncSchedule() {
    }

    public static List<SyncEvent> plan(SimulatedLogs logs, SimulationConfig config, long seed) {
        Random random = new Random(seed);
        List<SyncEvent> events = new ArrayList<>();

        for (Map.Entry<String, List<ScanRecord>> entry : logs.byGate().entrySet()) {
            List<ScanRecord> log = entry.getValue();
            int size = log.size();
            if (size == 0) {
                continue;
            }

            int batches = 1 + random.nextInt(config.maxSyncsPerGate());
            TreeSet<Integer> cuts = new TreeSet<>();
            while (cuts.size() < batches - 1 && cuts.size() < size - 1) {
                cuts.add(1 + random.nextInt(size - 1));
            }
            cuts.add(size); // the last batch is always the whole log

            for (int cut : cuts) {
                SyncEvent event = new SyncEvent(entry.getKey(), log.subList(0, cut));
                events.add(event);
                if (random.nextDouble() < config.duplicateSyncRate()) {
                    events.add(event); // sent again: the retry after a timeout
                }
            }
        }

        Collections.shuffle(events, random);
        return events;
    }

    /** How many scan records these uploads carry in total, counting resends. */
    public static long recordsSent(List<SyncEvent> events) {
        return events.stream().mapToLong(event -> event.records().size()).sum();
    }
}
