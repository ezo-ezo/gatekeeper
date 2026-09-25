package dev.gatekeeper.persistence;

import dev.gatekeeper.gate.ScanRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * The row-level shape of a {@link ScanRecord}. The unique constraint mirrors,
 * at the database level, the same identity {@link ScanRecord}'s value
 * equality already gives the in-memory {@code Reconciler}: two rows for the
 * same gate, ticket, time and outcome are the same scan.
 */
@Entity
@Table(
        name = "scan_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_scan_identity",
                columnNames = {"gate_id", "ticket_id", "scanned_at", "accepted"}))
class ScanRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gate_id", nullable = false)
    private String gateId;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(nullable = false)
    private boolean accepted;

    /** Required by JPA; not for application use. */
    protected ScanRecordEntity() {
    }

    private ScanRecordEntity(String gateId, String ticketId, Instant scannedAt, boolean accepted) {
        this.gateId = gateId;
        this.ticketId = ticketId;
        this.scannedAt = scannedAt;
        this.accepted = accepted;
    }

    static ScanRecordEntity fromDomain(ScanRecord record) {
        return new ScanRecordEntity(record.gateId(), record.ticketId(), record.scannedAt(), record.accepted());
    }

    ScanRecord toDomain() {
        return new ScanRecord(gateId, ticketId, scannedAt, accepted);
    }
}
