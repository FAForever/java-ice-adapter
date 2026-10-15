package com.faforever.iceadapter.ice;

public interface ModuleBase {

    default void start() {}

    default void stop() {}

    default void init() {}

    default Boolean isRunning() {
        return null;
    }

    default Boolean isEnabled() {
        return null;
    }

    default void enable() {}

    default void disable() {}
}
