package dev.gatekeeper.api.dto;

import dev.gatekeeper.gate.ScanRecord;

import java.time.Instant;

/** A scan as shown in a reconciliation report. */
public record ScanRecordView(String gateId, Instant scannedAt) {

    public static ScanRecordView from(ScanRecord record) {
        return new ScanRecordView(record.gateId(), record.scannedAt());
    }
}
