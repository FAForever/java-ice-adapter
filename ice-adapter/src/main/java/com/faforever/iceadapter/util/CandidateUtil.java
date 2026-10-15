package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import java.util.concurrent.atomic.AtomicInteger;

public class CandidateUtil {
    private static final AtomicInteger candidateIDFactory = new AtomicInteger(0);

    /**
     * Converts a WebRTC candidate line (e.g. "candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host...")
     * into a {@link CandidatePacket}.
     */
    public static CandidatePacket webRtcCandidateToPacket(String candidateStr) {
        if (candidateStr == null) {
            return null;
        }
        String line = candidateStr.trim();
        if (line.startsWith("a=")) {
            line = line.substring(2).trim();
        }
        if (!line.startsWith("candidate:")) {
            return null;
        }
        String rest = line.substring("candidate:".length()).trim();
        String[] parts = rest.split("\\s+");
        if (parts.length < 8) {
            return null;
        }
        try {
            String foundation = parts[0];
            String protocol = parts[2].toLowerCase();
            long priority = Long.parseLong(parts[3]);
            String ip = parts[4];
            int port = Integer.parseInt(parts[5]);
            String typeStr = parts[7].toLowerCase();
            CandidateType type =
                    switch (typeStr) {
                        case "host" -> CandidateType.HOST_CANDIDATE;
                        case "srflx" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
                        case "prflx" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
                        case "relay" -> CandidateType.RELAYED_CANDIDATE;
                        default -> CandidateType.HOST_CANDIDATE;
                    };

            String relAddr = null;
            int relPort = 0;
            int generation = 0;

            for (int i = 8; i < parts.length - 1; i++) {
                if ("raddr".equalsIgnoreCase(parts[i])) {
                    relAddr = parts[i + 1];
                } else if ("rport".equalsIgnoreCase(parts[i])) {
                    try {
                        relPort = Integer.parseInt(parts[i + 1]);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("generation".equalsIgnoreCase(parts[i])) {
                    try {
                        generation = Integer.parseInt(parts[i + 1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            return new CandidatePacket(
                    foundation,
                    protocol,
                    priority,
                    ip,
                    port,
                    type,
                    generation,
                    String.valueOf(candidateIDFactory.getAndIncrement()),
                    relAddr,
                    relPort);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a {@link CandidatePacket} back to a standard WebRTC candidate SDP line.
     */
    public static String candidatePacketToWebRtcString(CandidatePacket cp) {
        if (cp == null) {
            return null;
        }
        String typeStr =
                switch (cp.type()) {
                    case HOST_CANDIDATE, LOCAL_CANDIDATE -> "host";
                    case SERVER_REFLEXIVE_CANDIDATE, STUN_CANDIDATE -> "srflx";
                    case PEER_REFLEXIVE_CANDIDATE -> "prflx";
                    case RELAYED_CANDIDATE -> "relay";
                    default -> "host";
                };
        StringBuilder sb = new StringBuilder();
        sb.append("candidate:")
                .append(cp.foundation())
                .append(" 1 ")
                .append(cp.protocol().toLowerCase())
                .append(" ")
                .append(cp.priority())
                .append(" ")
                .append(cp.ip())
                .append(" ")
                .append(cp.port())
                .append(" typ ")
                .append(typeStr);
        if (cp.relAddr() != null && cp.relPort() > 0) {
            sb.append(" raddr ").append(cp.relAddr()).append(" rport ").append(cp.relPort());
        }
        sb.append(" generation ").append(cp.generation());
        return sb.toString();
    }
}
