package dev.gatekeeper.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

    @Test
    void issueFillsInAGeneratedIdAndSecret() {
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", NOW);

        assertThat(ticket.id()).isNotBlank();
        assertThat(ticket.eventId()).isEqualTo("event-1");
        assertThat(ticket.holderName()).isEqualTo("Asha Rao");
        assertThat(ticket.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void twoIssuedTicketsHaveDifferentIdsAndSecrets() {
        Ticket a = Ticket.issue("event-1", "Asha Rao", NOW);
        Ticket b = Ticket.issue("event-1", "Asha Rao", NOW);

        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(a.secret()).isNotEqualTo(b.secret());
    }

    @Test
    void currentCodeMatchesTheGeneratorDirectly() {
        Ticket ticket = Ticket.issue("event-1", "Asha Rao", NOW);
        assertThat(ticket.currentCode(NOW)).isEqualTo(TotpTokenGenerator.generate(ticket.secret(), NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankEventId(String blank) {
        assertThatThrownBy(() -> Ticket.issue(blank, "Asha Rao", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankHolderName(String blank) {
        assertThatThrownBy(() -> Ticket.issue("event-1", blank, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullFields() {
        TicketSecret secret = TicketSecret.generate();
        assertThatThrownBy(() -> new Ticket(null, "event-1", "Asha Rao", secret, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Ticket("t1", "event-1", "Asha Rao", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
