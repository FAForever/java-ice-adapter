package com.faforever.iceadapter.webrtc;

import static com.faforever.iceadapter.ice.IceState.*;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.util.LockUtil;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * WebRTC-based connect service for non-controlling peer (answerer).
 * Replaces ConnectServiceNotControlledImpl when --transport=webrtc is used.
 * Handles SDP answer creation and ICE candidate exchange via RPC.
 */
@Slf4j
public class WebRtcConnectServiceNotControlledImpl extends WebRtcConnectServiceCommon implements WebRtcConnectService {

    private final Map<Integer, String> lastProcessedOfferSdpByPeer = new ConcurrentHashMap<>();

    public WebRtcConnectServiceNotControlledImpl(
            MessageService messageService, IceGameSession iceGameSession, IceAsync iceAsync) {
        super(messageService, iceGameSession, iceAsync);
    }

    @Override
    void onIceStateNew(Peer peer) {
        // Nothing to do. Job for controlled peer
    }

    @Override
    void onIceStateGathering(Peer peer) {
        // Nothing to do. Job for controlled peer
    }

    @Override
    void onIceAwaitingCandidates(Peer peer) {
        // Nothing to do. Job for controlled peer
    }

    @Override
    void onIceStateCompleted(Peer peer) {
        log.info("ICE state completed");
    }

    @Override
    void onIceStateDisconnected(Peer peer, IceState oldState) {
        lastProcessedOfferSdpByPeer.remove(peer.getRemoteId());
        onDisconnected(peer, oldState);
    }

    @Override
    void onIceStateChecking(Peer peer) {
        boolean connected = checking(peer);
        if (connected) {
            peer.setIceState(CONNECTED);
        } else {
            connectLost(peer, true);
        }
    }

    @Override
    void onIceStateConnected(Peer peer) {
        onConnected(peer);
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (peer == null) {
            return;
        }
        lastProcessedOfferSdpByPeer.remove(peer.getRemoteId());
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> connectLost(peer, false, clearIceState));
    }

    @Override
    public void onMessageFromRPC(Peer peer, Object message) {
        if (message instanceof CandidatesMessage candidatesMessage) {
            logicOnCandidatesMessageReceived(peer, candidatesMessage);
        }
    }

    private void logicOnCandidatesMessageReceived(Peer peer, CandidatesMessage message) {
        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, discarding signaling message");
            return;
        }

        log.debug(
                "Got CandidatesMessage for answerer peer: ufrag={}, candidates={}",
                message.ufrag(),
                message.candidates().size());

        if (!message.isOffer()
                && !(message.password() != null && message.password().contains("v=0"))) {
            log.warn(
                    "Answerer peer received non-offer CandidatesMessage (ufrag={}), ignoring late message",
                    message.ufrag());
            return;
        }

        String offerSdp = message.password();
        String lastSdp = lastProcessedOfferSdpByPeer.get(peer.getRemoteId());
        WebRtcSession currentSession = peer.getWebRtcSession();

        // 1. If already CONNECTED with active session, ignore late/duplicate offers to protect live connection
        if (peer.getIceState() == CONNECTED && currentSession != null && currentSession.isConnected()) {
            log.warn(
                    "Answerer peer received Offer while already CONNECTED with active session for peer {}, ignoring late message",
                    peer.getPeerIdentifier());
            return;
        }

        // 2. Check if this is a duplicate of the offer we are already processing or connected with
        if (offerSdp != null && offerSdp.equals(lastSdp) && currentSession != null && !currentSession.isClosed()) {
            log.info(
                    "Answerer peer received duplicate Offer SDP for peer {}, re-flushing answer via RPC",
                    peer.getPeerIdentifier());
            flushSignalingViaRpc(peer);
            return;
        }

        // New offer (initial connection or reconnect)
        lastProcessedOfferSdpByPeer.put(peer.getRemoteId(), offerSdp != null ? offerSdp : "");

        IceState iceState = peer.getIceState();
        if (iceState != NEW && iceState != DISCONNECTED) {
            peer.setIceStateWithoutTrigger(DISCONNECTED);
            log.info(
                    "Offer received while in state {}, restarting the connection for peer {}",
                    iceState,
                    peer.getPeerIdentifier());
            onDisconnected(peer, iceState);
        }

        // Create WebRTC session
        createWebRtcSession(peer, false);
        WebRtcSignalingService signalingService = peer.getWebRtcSignalingService();

        if (signalingService == null) {
            log.error("WebRTC signaling service is null for peer {}", peer.getPeerIdentifier());
            return;
        }

        // Process remote offer and create answer (Vanilla ICE: all remote candidates bundled)
        signalingService.processRemoteOffer(message.password(), message.candidates());

        // Send answer via RPC
        flushSignalingViaRpc(peer);

        peer.setIceState(CHECKING);
    }
}
