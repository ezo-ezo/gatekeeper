package dev.gatekeeper.api.dto;

/** Acknowledges a sync: how many scans this call sent, and the state of the world after. */
public record SyncResponse(String gateId, int scansReceived, int totalScansConsidered) {
}
