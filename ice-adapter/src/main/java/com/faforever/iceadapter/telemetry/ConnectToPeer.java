package com.faforever.iceadapter.telemetry;

import java.util.UUID;

public record ConnectToPeer(UUID messageId, int peerPlayerId, String peerName,
                            boolean localOffer) implements OutgoingMessageV1 {
}