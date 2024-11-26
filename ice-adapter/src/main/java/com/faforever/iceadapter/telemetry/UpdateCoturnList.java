package com.faforever.iceadapter.telemetry;

import java.util.Collection;
import java.util.UUID;

public record UpdateCoturnList(UUID messageId, String connectedHost, Collection<CoturnServer> knownServers)
        implements OutgoingMessageV1 {}
