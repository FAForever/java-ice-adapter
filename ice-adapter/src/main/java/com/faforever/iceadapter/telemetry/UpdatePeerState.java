package com.faforever.iceadapter.telemetry;

import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.IceState;
import java.util.UUID;

public record UpdatePeerState(
        UUID messageId,
        int peerPlayerId,
        IceState iceState,
        CandidateType localCandidate,
        CandidateType remoteCandidate)
        implements OutgoingMessageV1 {
    @Override
    public String getType() {
        return getClass().getSimpleName();
    }
}
