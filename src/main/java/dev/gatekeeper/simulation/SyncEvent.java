package dev.gatekeeper.simulation;

import dev.gatekeeper.gate.ScanRecord;

import java.util.List;

/** One upload: a gate sending some of its log to the server. */
public record SyncEvent(String gateId, List<ScanRecord> records) {

    public SyncEvent {
        records = List.copyOf(records);
    }
}
