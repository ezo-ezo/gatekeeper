package dev.gatekeeper.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface ScanRecordRepository extends JpaRepository<ScanRecordEntity, Long> {

    /**
     * Every stored scan by this gate for any of these tickets. It may return
     * more than the caller is looking for (the same ticket at another time, or
     * with another outcome); the caller compares full records.
     */
    List<ScanRecordEntity> findByGateIdAndTicketIdIn(String gateId, Collection<String> ticketIds);
}
