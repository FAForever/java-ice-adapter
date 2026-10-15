package com.faforever.iceadapter.webrtc;

import static com.faforever.iceadapter.ice.IceState.*;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.util.LockUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * WebRTC-based connect service for controlling peer (offerer).
 * Replaces ConnectServiceControlledImpl when --transport=webrtc is used.
 * Handles SDP offer creation and ICE candidate exchange via RPC.
 */
@Slf4j
public class WebRtcConnectServiceControlledImpl extends WebRtcConnectServiceCommon implements WebRtcConnectService {

    public WebRtcConnectServiceControlledImpl(
            MessageService messageService, IceGameSession iceGameSession, IceAsync iceAsync) {
        super(messageService, iceGameSession, iceAsync);
    }

    @Override
    void onIceStateNew(Peer peer) {
        // Create WebRTC session
        createWebRtcSession(peer, true);
        peer.setIceState(GATHERING);
    }

    @Override
    void onIceStateGathering(Peer peer) {
        // Gather candidates (create offer for WebRTC)
        gatherCandidates(peer);
        peer.setIceState(AWAITING_CANDIDATES);
    }

    @Override
    void onIceAwaitingCandidates(Peer peer) {
        // Make sure to abort the connection process and reinitiate when we haven't received an answer to our offer in 6
        // seconds, candidate packet was probably lost
        final int currentAwaitingCandidatesEventId =
                peer.getAwaitingCandidatesEventId().incrementAndGet();
        iceAsync.runAsyncDelay(
                peer, () -> asyncTimeoutAwaitingCandidates(currentAwaitingCandidatesEventId, peer), 6000);
    }

    @Override
    void onIceStateDisconnected(Peer peer, IceState oldState) {
        onDisconnected(peer, oldState);
        tryReInitState(peer, oldState);
    }

    @Override
    void onIceStateCompleted(Peer peer) {
        log.info("ICE state completed");
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
        peer.getReInitEventId().incrementAndGet();
        onConnected(peer);
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (peer == null) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> connectLost(peer, false, clearIceState));
    }

    private void tryReInitState(Peer peer, IceState oldState) {
        final int currentEventId = peer.getReInitEventId().incrementAndGet();
        iceAsync.runAsyncDelay(
                peer,
                () -> {
                    if (currentEventId != peer.getReInitEventId().get()) {
                        log.debug("Skipping stale tryReInitState for peer {}", peer.getPeerIdentifier());
                        return;
                    }
                    if (peer.getIceState() == DISCONNECTED) {
                        peer.setIceState(NEW);
                    } else {
                        log.debug(
                                "Skipping tryReInitState, peer {} is already in state {}",
                                peer.getPeerIdentifier(),
                                peer.getIceState());
                    }
                },
                oldState == CONNECTED ? 1000 : 5000);
    }

    @Override
    public void onMessageFromRPC(Peer peer, Object message) {
        if (message instanceof CandidatesMessage candidatesMessage) {
            processCandidatesMessage(peer, candidatesMessage);
        }
    }

    private void processCandidatesMessage(Peer peer, CandidatesMessage message) {
        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, discarding signaling message");
            return;
        }

        // Guard against late/delayed messages after connection is established or already checking
        if (peer.getIceState() != AWAITING_CANDIDATES) {
            if (peer.getIceState() == CHECKING
                    && (message.isAnswer()
                            || (message.password() != null && message.password().contains("v=0")))) {
                log.info(
                        "Controlled peer received duplicate Answer CandidatesMessage in CHECKING state for peer {}, ignoring duplicate",
                        peer.getPeerIdentifier());
                return;
            }
            log.warn(
                    "Controlled peer received CandidatesMessage in state {}, ignoring late message",
                    peer.getIceState());
            return;
        }

        log.debug(
                "Got CandidatesMessage for controlled peer: ufrag={}, candidates={}",
                message.ufrag(),
                message.candidates().size());

        WebRtcSignalingService signalingService = peer.getWebRtcSignalingService();
        if (signalingService == null) {
            log.error("WebRTC signaling service is null for peer {}", peer.getPeerIdentifier());
            return;
        }

        if (message.isAnswer()
                || (message.password() != null && message.password().contains("v=0"))) {
            // Transition to CHECKING immediately to reject late duplicate messages
            peer.setIceState(CHECKING);
            signalingService.processRemoteAnswer(message.password(), message.candidates());
        }
    }
}
