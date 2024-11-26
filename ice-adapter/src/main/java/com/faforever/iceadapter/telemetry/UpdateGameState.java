package com.faforever.iceadapter.telemetry;

import com.faforever.iceadapter.gpgnet.GameState;
import java.util.UUID;

public record UpdateGameState(UUID messageId, GameState newState) implements OutgoingMessageV1 {}
