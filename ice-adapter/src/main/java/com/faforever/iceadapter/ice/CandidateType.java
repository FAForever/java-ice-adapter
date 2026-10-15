package com.faforever.iceadapter.ice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Candidate type enum representing standard ICE candidate types.
 */
@RequiredArgsConstructor
@Getter
public enum CandidateType {
    HOST_CANDIDATE("host"),
    SERVER_REFLEXIVE_CANDIDATE("srflx"),
    PEER_REFLEXIVE_CANDIDATE("prflx"),
    RELAYED_CANDIDATE("relay"),
    LOCAL_CANDIDATE("local"),
    STUN_CANDIDATE("stun");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
