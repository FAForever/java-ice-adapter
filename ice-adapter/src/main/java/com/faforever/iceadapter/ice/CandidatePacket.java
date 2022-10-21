package com.faforever.iceadapter.ice;

import org.ice4j.ice.CandidateType;

/**
 * Represents a candidate to be sent/received via IceMessage
 */
public record CandidatePacket(
        String foundation,
        String protocol,
        long priority,
        String ip,
        int port,
        CandidateType type,
        int generation,
        String id,
        String relAddr,
        int relPort
) implements Comparable<CandidatePacket> {
    @Override
    public int compareTo(CandidatePacket o) {
        return (int) (o.priority - this.priority);
    }
}
