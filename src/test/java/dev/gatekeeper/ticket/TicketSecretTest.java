package dev.gatekeeper.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketSecretTest {

    @Test
    void base32RoundTripsToAnEqualSecret() {
        TicketSecret original = TicketSecret.generate();
        TicketSecret decoded = TicketSecret.fromBase32(original.toBase32());
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void generatedSecretsAreDifferent() {
        assertThat(TicketSecret.generate()).isNotEqualTo(TicketSecret.generate());
    }

    @Test
    void equalityIsByKeyBytesNotIdentity() {
        TicketSecret a = TicketSecret.of(new byte[]{1, 2, 3, 4, 5});
        TicketSecret b = TicketSecret.of(new byte[]{1, 2, 3, 4, 5});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void ofRejectsAnEmptyKey() {
        assertThatThrownBy(() -> TicketSecret.of(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverRevealsTheKey() {
        TicketSecret secret = TicketSecret.of(new byte[]{1, 2, 3, 4, 5});
        assertThat(secret.toString())
                .doesNotContain(secret.toBase32())
                .contains("REDACTED");
    }
}
