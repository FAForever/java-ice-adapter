package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;

import java.util.Collections;

@Slf4j
public class CandidateUtil {

    public static int candidateIDFactory = 0;

    public static CandidatesMessage packCandidates(int srcId, int destId, Agent agent, Component component, boolean allowHost, boolean allowReflexive, boolean allowRelay) {
        if(! allowHost || ! allowReflexive || !allowRelay) {
            log.info("Peer {}: Disallowing own candidates, host: {}, reflexive: {}, relay: {}", destId, allowHost, allowReflexive, allowRelay);
        }

        CandidatesMessage localCandidatesMessage = new CandidatesMessage(srcId, destId, agent.getLocalPassword(), agent.getLocalUfrag());

        for (LocalCandidate localCandidate : component.getLocalCandidates()) {

            CandidatePacket candidatePacket = new CandidatePacket(
                    localCandidate.getFoundation(),
                    localCandidate.getTransportAddress().getTransport().toString(),
                    localCandidate.getPriority(),
                    localCandidate.getTransportAddress().getHostAddress(),
                    localCandidate.getTransportAddress().getPort(),
                    CandidateType.valueOf(localCandidate.getType().name()),
                    agent.getGeneration(),
                    String.valueOf(candidateIDFactory++)
            );

            if (localCandidate.getRelatedAddress() != null) {
                candidatePacket.setRelAddr(localCandidate.getRelatedAddress().getHostAddress());
                candidatePacket.setRelPort(localCandidate.getRelatedAddress().getPort());
            }

            //Candidate type LOCAL and STUN can never occur as they are deprecated and not
            if (allowHost && localCandidate.getType().equals(CandidateType.HOST_CANDIDATE)) {
                localCandidatesMessage.getCandidates().add(candidatePacket);
            }
            if (allowReflexive &&
                    (localCandidate.getType().equals(CandidateType.SERVER_REFLEXIVE_CANDIDATE) || localCandidate.getType().equals(CandidateType.PEER_REFLEXIVE_CANDIDATE))) {
                localCandidatesMessage.getCandidates().add(candidatePacket);
            }
            if (allowRelay && localCandidate.getType().equals(CandidateType.RELAYED_CANDIDATE)) {
                localCandidatesMessage.getCandidates().add(candidatePacket);
            }
        }

        return localCandidatesMessage;
    }

    public static void unpackCandidates(CandidatesMessage remoteCandidatesMessage, Agent agent, Component component, IceMediaStream mediaStream, boolean allowHost, boolean allowReflexive, boolean allowRelay) {
        Collections.sort(remoteCandidatesMessage.getCandidates());

        if(! allowHost || ! allowReflexive || !allowRelay) {
            log.info("Peer {}: Disallowing incoming candidates, host: {}, reflexive: {}, relay: {}", remoteCandidatesMessage.getSrcId(), allowHost, allowReflexive, allowRelay);
        }

        //Set candidates
        mediaStream.setRemotePassword(remoteCandidatesMessage.getPassword());
        mediaStream.setRemoteUfrag(remoteCandidatesMessage.getUfrag());
        for (CandidatePacket remoteCandidatePacket : remoteCandidatesMessage.getCandidates()) {

            if (remoteCandidatePacket.getGeneration() == agent.getGeneration()
                    && remoteCandidatePacket.getIp() != null && remoteCandidatePacket.getPort() > 0) {

                TransportAddress mainAddress = new TransportAddress(remoteCandidatePacket.getIp(), remoteCandidatePacket.getPort(), Transport.parse(remoteCandidatePacket.getProtocol().toLowerCase()));

                RemoteCandidate relatedCandidate = null;
                if (remoteCandidatePacket.getRelAddr() != null && remoteCandidatePacket.getRelPort() > 0) {
                    TransportAddress relatedAddr = new TransportAddress(remoteCandidatePacket.getRelAddr(), remoteCandidatePacket.getRelPort(), Transport.parse(remoteCandidatePacket.getProtocol().toLowerCase()));
                    relatedCandidate = component.findRemoteCandidate(relatedAddr);
                }

                RemoteCandidate remoteCandidate = new RemoteCandidate(
                        mainAddress,
                        component,
                        CandidateType.parse(remoteCandidatePacket.getType().toString()),//Expected to not return LOCAL or STUN (old names for host and srflx)
                        remoteCandidatePacket.getFoundation(),
                        remoteCandidatePacket.getPriority(),
                        relatedCandidate
                );

                //Candidate type LOCAL and STUN can never occur as they are deprecated and not
                if (allowHost && remoteCandidate.getType().equals(CandidateType.HOST_CANDIDATE)) {
                    component.addRemoteCandidate(remoteCandidate);
                }
                if (allowReflexive &&
                        (remoteCandidate.getType().equals(CandidateType.SERVER_REFLEXIVE_CANDIDATE) || remoteCandidate.getType().equals(CandidateType.PEER_REFLEXIVE_CANDIDATE))) {
                    component.addRemoteCandidate(remoteCandidate);
                }
                if (allowRelay && remoteCandidate.getType().equals(CandidateType.RELAYED_CANDIDATE)) {
                    component.addRemoteCandidate(remoteCandidate);
                }
            }
        }
    }
}
