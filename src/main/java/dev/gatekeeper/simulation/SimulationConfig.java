package dev.gatekeeper.simulation;

import java.time.Duration;

/**
 * Everything that shapes one simulated event: how many gates and tickets,
 * how badly people and gates misbehave, and how the gates' uploads arrive.
 * The same config (seed included) always produces the same scans.
 *
 * @param gates              entrances, each with its own clock and its own log
 * @param tickets            tickets issued and provisioned to every gate
 * @param attendanceRate     share of tickets whose holder turns up at all
 * @param sharedTicketRate   share of attendees whose ticket is also presented at 1 or 2 other
 *                           gates while every gate is offline (a resold or relayed ticket, or
 *                           two people racing through different doors)
 * @param sameGateRescanRate share of attendees who scan a second time at the gate they entered by
 * @param invalidCodeRate    chance any one presentation shows a stale or wrong code
 * @param forgedRate         presentations of tickets that were never issued, as a fraction of tickets
 * @param offlineWindow      how long the gates are cut off; first presentations fall within it
 * @param sharedGap          longest wait between a shared ticket's presentations at different gates
 * @param maxClockSkew       each gate's clock is wrong by up to this much, in either direction
 * @param maxSyncsPerGate    each gate uploads its log in 1..N batches, each batch re-sending the
 *                           whole log so far (overlapping, the way a naive retry does)
 * @param duplicateSyncRate  chance an upload is sent twice (a retry after a timeout)
 * @param seed               makes the run reproducible
 */
public record SimulationConfig(
        int gates,
        int tickets,
        double attendanceRate,
        double sharedTicketRate,
        double sameGateRescanRate,
        double invalidCodeRate,
        double forgedRate,
        Duration offlineWindow,
        Duration sharedGap,
        Duration maxClockSkew,
        int maxSyncsPerGate,
        double duplicateSyncRate,
        long seed) {

    public SimulationConfig {
        require(gates >= 1, "gates must be at least 1");
        require(tickets >= 1, "tickets must be at least 1");
        requireRate(attendanceRate, "attendanceRate");
        requireRate(sharedTicketRate, "sharedTicketRate");
        requireRate(sameGateRescanRate, "sameGateRescanRate");
        requireRate(invalidCodeRate, "invalidCodeRate");
        requireRate(forgedRate, "forgedRate");
        requireRate(duplicateSyncRate, "duplicateSyncRate");
        require(offlineWindow.toSeconds() >= 1, "offlineWindow must be at least one second");
        require(sharedGap.toSeconds() >= 1, "sharedGap must be at least one second");
        require(!maxClockSkew.isNegative(), "maxClockSkew must not be negative");
        require(maxSyncsPerGate >= 1, "maxSyncsPerGate must be at least 1");
    }

    /** A moderate event: a dozen gates, thousands of tickets, a few percent of everything going wrong. */
    public static SimulationConfig defaults() {
        return new SimulationConfig(
                12, 5_000,
                0.92, 0.06, 0.08, 0.03, 0.01,
                Duration.ofHours(2), Duration.ofMinutes(10), Duration.ofSeconds(20),
                3, 0.15, 42);
    }

    public SimulationConfig withGates(int gates) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withTickets(int tickets) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withSharedTicketRate(double rate) {
        return new SimulationConfig(gates, tickets, attendanceRate, rate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withSharedGap(Duration gap) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, gap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withOfflineWindow(Duration window) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, window, sharedGap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withMaxClockSkew(Duration skew) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, skew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withMaxSyncsPerGate(int syncs) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, syncs,
                duplicateSyncRate, seed);
    }

    public SimulationConfig withDuplicateSyncRate(double rate) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, maxSyncsPerGate,
                rate, seed);
    }

    public SimulationConfig withSeed(long seed) {
        return new SimulationConfig(gates, tickets, attendanceRate, sharedTicketRate, sameGateRescanRate,
                invalidCodeRate, forgedRate, offlineWindow, sharedGap, maxClockSkew, maxSyncsPerGate,
                duplicateSyncRate, seed);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireRate(double value, String name) {
        require(value >= 0 && value <= 1, name + " must be between 0 and 1");
    }
}
