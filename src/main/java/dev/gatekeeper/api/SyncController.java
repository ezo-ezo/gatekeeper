package dev.gatekeeper.api;

import dev.gatekeeper.api.dto.ScanRecordRequest;
import dev.gatekeeper.api.dto.SyncRequest;
import dev.gatekeeper.api.dto.SyncResponse;
import dev.gatekeeper.gate.ScanRecord;
import dev.gatekeeper.scanlog.ScanLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Where a gate delivers its scan log once it has connectivity again. A gate
 * may sync its whole log, a batch of it, or retry an overlapping slice after
 * a dropped connection — {@link ScanLog#store} treats all of these the same
 * way, since re-sending an already-seen scan is a no-op.
 */
@RestController
@RequestMapping("/gates")
public class SyncController {

    private final ScanLog scanLog;

    public SyncController(ScanLog scanLog) {
        this.scanLog = scanLog;
    }

    @PostMapping("/{gateId}/sync")
    public SyncResponse sync(@PathVariable String gateId, @Valid @RequestBody SyncRequest request) {
        List<ScanRecord> scans = request.scans().stream().map(scan -> toScanRecord(gateId, scan)).toList();

        scanLog.store(scans);

        return new SyncResponse(gateId, scans.size(), (int) scanLog.totalStoredScans());
    }

    private static ScanRecord toScanRecord(String gateId, ScanRecordRequest scan) {
        return new ScanRecord(gateId, scan.ticketId(), scan.scannedAt(), scan.accepted());
    }
}
