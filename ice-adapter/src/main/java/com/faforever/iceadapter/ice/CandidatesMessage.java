package com.faforever.iceadapter.ice;

import java.util.List;

/**
 * Represents and IceMessage, consists out of candidates and ufrag aswell as password
 */
public record CandidatesMessage(
        int srcId, int destId, String password, String ufrag, List<CandidatePacket> candidates) {
    public CandidatesMessage {
        candidates = List.copyOf(candidates);
    }
}
