package com.faforever.iceadapter.ice.peer.modules;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AllowCombination {
    ALL(true, true, true),
    REFLEXIVE_RELAY(false, true, true),
    HOST_RELAY(true, false, true),
    RELAY(false, false, true);
    private final boolean allowHost;
    private final boolean allowReflexive;
    private final boolean allowRelay;
}
