package dev.gatekeeper.gate;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanRecordTest {

    @Test
    void timestampsAreCanonicalisedToMilliseconds() {
        ScanRecord record = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.123456789Z"), true);

        assertThat(record.scannedAt()).isEqualTo(Instant.parse("2026-09-26T18:00:00.123Z"));
    }

    @Test
    void twoTimestampsThatDifferOnlyBelowAMillisecondAreTheSameScan() {
        // The property that keeps a resent scan recognisable: what a gate with a
        // nanosecond clock produced and what came back from a database or JSON
        // round trip must compare equal.
        ScanRecord fromGate = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.123456789Z"), true);
        ScanRecord afterRoundTrip = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.123457Z"), true);

        assertThat(fromGate).isEqualTo(afterRoundTrip).hasSameHashCodeAs(afterRoundTrip);
    }

    @Test
    void scansAMillisecondApartRemainDistinct() {
        ScanRecord a = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.123Z"), true);
        ScanRecord b = new ScanRecord("gate-1", "t1", Instant.parse("2026-09-26T18:00:00.124Z"), true);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void wholeSecondTimestampsAreLeftAlone() {
        Instant whole = Instant.parse("2026-09-26T18:00:00Z");
        assertThat(new ScanRecord("gate-1", "t1", whole, true).scannedAt()).isEqualTo(whole);
    }

    @Test
    void rejectsNulls() {
        Instant now = Instant.parse("2026-09-26T18:00:00Z");
        assertThatThrownBy(() -> new ScanRecord(null, "t1", now, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScanRecord("gate-1", null, now, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScanRecord("gate-1", "t1", null, true)).isInstanceOf(NullPointerException.class);
    }
}
