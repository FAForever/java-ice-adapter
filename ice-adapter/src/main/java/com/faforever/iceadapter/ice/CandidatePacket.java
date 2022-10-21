package com.faforever.iceadapter.ice;

import lombok.Data;
import org.ice4j.ice.CandidateType;

/**
 * Represents a candidate to be sent/received via IceMessage
 */
@Data
public class CandidatePacket implements Comparable<CandidatePacket> {
    private final String foundation;
    private final String protocol;
    private final long priority;
    private final String ip;
    private final int port;
    private final CandidateType type;
    private final int generation;
    private final String id;

    private String relAddr;
    private int relPort;

    @Override
    public int compareTo(CandidatePacket o) {
        return (int) (o.priority - this.priority);
    }
}
