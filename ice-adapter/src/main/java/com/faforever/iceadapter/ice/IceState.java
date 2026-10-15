package com.faforever.iceadapter.ice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * IceState, does not match WebRTC states, represents peer connection "lifecycle"
 */
@Getter
@RequiredArgsConstructor
public enum IceState {
    NEW("new"),
    GATHERING("gathering"),
    AWAITING_CANDIDATES("awaitingCandidates"),
    CHECKING("checking"),
    CONNECTED("connected"),
    COMPLETED("completed"),
    DISCONNECTED("disconnected");

    private final String message;

    @Override
    public String toString() {
        return message;
    }
}
