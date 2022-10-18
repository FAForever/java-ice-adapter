package com.faforever.iceadapter.telemetry;

import java.util.List;
import java.util.UUID;

public record UpdateCoturnList(UUID messageId, String connectedHost,
                               List<CoturnServer> knownServers) implements OutgoingMessageV1 {
}