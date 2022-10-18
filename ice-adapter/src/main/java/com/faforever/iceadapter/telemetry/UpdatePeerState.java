package com.faforever.iceadapter.telemetry;

import com.faforever.iceadapter.ice.IceState;
import org.ice4j.ice.CandidateType;

import java.util.UUID;

public record UpdatePeerState(
        UUID messageId, int peerPlayerId, IceState iceState, CandidateType localCandidate,
        CandidateType remoteCandidate) implements OutgoingMessageV1 {
}