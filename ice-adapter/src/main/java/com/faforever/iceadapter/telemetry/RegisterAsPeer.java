package com.faforever.iceadapter.telemetry;

import java.util.UUID;

public record RegisterAsPeer(UUID messageId, String adapterVersion, String userName) implements OutgoingMessageV1 {}
