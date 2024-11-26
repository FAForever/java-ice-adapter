package com.faforever.iceadapter.gpgnet;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * GameState as sent by FA,
 * ENDED was added by the FAF project
 */
@Getter
@RequiredArgsConstructor
public enum GameState {
    NONE("None"),
    IDLE("Idle"),
    LOBBY("Lobby"),
    LAUNCHING("Launching"),
    ENDED("Ended"); // TODO: more?

    private final String name;

    public static GameState getByName(String name) {
        return Arrays.stream(GameState.values())
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
