package com.vltbr.minidrone.mavlink;

/** Immutable transport diagnostics exposed by the in-game link status command. */
public record MavlinkLinkStatus(
    boolean running,
    boolean socketBound,
    int localPort,
    String remoteHost,
    int remotePort,
    long receivedPackets,
    long receivedFrames,
    long transmittedFrames,
    long healthBeaconsSent,
    long lastInboundAtMs,
    long lastOutboundAtMs,
    int lastInboundMessageId,
    String lastInboundEndpoint,
    boolean mocapHealthEnabled
) {
    private static final long BACKEND_FRESHNESS_MS = 3_000L;

    public boolean backendSeen() {
        return receivedFrames > 0;
    }

    public boolean backendFresh(long nowMs) {
        return backendSeen()
            && lastInboundAtMs > 0
            && nowMs >= lastInboundAtMs
            && nowMs - lastInboundAtMs <= BACKEND_FRESHNESS_MS;
    }

    public LinkState state() {
        return state(System.currentTimeMillis());
    }

    public LinkState state(long nowMs) {
        if (!running || !socketBound) {
            return LinkState.DOWN;
        }
        return backendFresh(nowMs)
            ? LinkState.CONNECTED
            : LinkState.WAITING_FOR_BACKEND;
    }

    public enum LinkState {
        DOWN,
        WAITING_FOR_BACKEND,
        CONNECTED
    }
}
