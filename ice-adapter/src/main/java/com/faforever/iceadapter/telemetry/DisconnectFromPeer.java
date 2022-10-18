package com.faforever.iceadapter.telemetry;

import java.util.UUID;

public record DisconnectFromPeer(UUID messageId, int peerPlayerId) implements OutgoingMessageV1 {
}