package com.faforever.iceadapter.telemetry;

import java.time.Instant;
import java.util.UUID;

public record UpdatePeerConnectivity(UUID messageId, int peerPlayerId, Float averageRTT,
                                     Instant lastReceived) implements OutgoingMessageV1 {
}