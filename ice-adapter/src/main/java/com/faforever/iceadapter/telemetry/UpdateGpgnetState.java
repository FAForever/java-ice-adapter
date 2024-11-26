package com.faforever.iceadapter.telemetry;

import java.util.UUID;

public record UpdateGpgnetState(UUID messageId, String newState) implements OutgoingMessageV1 {}
