package com.faforever.iceadapter.ice;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents and IceMessage, consists out of candidates and ufrag aswell as password
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidatesMessage(
        int srcId, int destId, String password, String ufrag, List<CandidatePacket> candidates) {
    public CandidatesMessage {
        candidates = candidates != null ? List.copyOf(candidates) : List.of();
    }

    @JsonIgnore
    public boolean isOffer() {
        return (ufrag != null && ufrag.startsWith("offer"))
                || (password != null && password.contains("v=0") && (ufrag == null || !ufrag.startsWith("answer")));
    }

    @JsonIgnore
    public boolean isAnswer() {
        return (ufrag != null && ufrag.startsWith("answer"))
                || (password != null && password.contains("v=0") && (ufrag == null || !ufrag.startsWith("offer")));
    }

    @JsonIgnore
    public boolean isCandidate() {
        return (ufrag != null && ufrag.startsWith("candidate"))
                || (password != null && password.startsWith("candidate:"));
    }

    @JsonIgnore
    public String toStrCandidates() {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        return candidates.stream()
                .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                .collect(Collectors.joining(", "));
    }
}
