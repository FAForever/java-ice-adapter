package com.faforever.iceadapter.webrtc;

import static com.faforever.iceadapter.ice.IceState.*;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.webrtc.WebRtcPeerToPeerListenerModule;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.util.LockUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Common logic for WebRTC-based ICE connection.
 * Replaces ConnectServiceCommon when --transport=webrtc is used.
 * Uses WebRTC Session instead of ice4j Agent/Component.
 * Signaling (SDP/ICE candidates) is sent/received via RPC.
 * Data transfer happens through WebRTC data channel.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class WebRtcConnectServiceCommon {
    protected static final String LOCK_CONNECT = "WebRtcConnectService";
    protected static final int TIMEOUT_ON_CHECKING = 15000;
    protected static final int LOST_CONNECT_DURATION = 5000;
    protected final MessageService messageService;
    protected final IceGameSession iceGameSession;
    protected final IceAsync iceAsync;

    public void onChangeIceState(Peer peer, IceState oldState, IceState iceState) {
        if (peer == null || iceState == null) {
            return;
        }
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
            switch (iceState) {
                case NEW -> onIceStateNew(peer);
                case GATHERING -> onIceStateGathering(peer);
                case AWAITING_CANDIDATES -> onIceAwaitingCandidates(peer);
                case CHECKING -> onIceStateChecking(peer);
                case CONNECTED -> onIceStateConnected(peer);
                case COMPLETED -> onIceStateCompleted(peer);
                case DISCONNECTED -> onIceStateDisconnected(peer, oldState);
                default -> log.error("Unknown Ice State {}", iceState);
            }
        });
    }

    abstract void onIceStateNew(Peer peer);

    abstract void onIceStateGathering(Peer peer);

    abstract void onIceAwaitingCandidates(Peer peer);

    abstract void onIceStateChecking(Peer peer);

    abstract void onIceStateConnected(Peer peer);

    abstract void onIceStateCompleted(Peer peer);

    abstract void onIceStateDisconnected(Peer peer, IceState oldState);

    // ==================== WebRTC Lifecycle ====================

    /**
     * Create WebRTC session and signaling service for the peer.
     */
    protected void createWebRtcSession(Peer peer, boolean isOfferer) {
        log.info("Creating WebRTC session for peer {} (offerer={})", peer.getPeerIdentifier(), isOfferer);

        // Close existing session if any
        closeWebRtcSession(peer);

        // Create WebRTC session
        WebRtcSession webRtcSession = new WebRtcSession(iceGameSession.getWebRtcConnectionFactory());

        // Set up signaling service
        WebRtcSignalingService signalingService =
                new WebRtcSignalingService(webRtcSession, peer.getFromId(), peer.getRemoteId());
        signalingService.setSignalingMessageSender(msg -> sendSignalingViaRpc(peer, msg));

        // Set up WebRTC session callbacks
        webRtcSession.init(
                isOfferer,
                iceGameSession.getIceServers(),
                iceGameSession.getOptions(),
                peer.getCombination(),
                // Message handler - data received from data channel
                (data, isBinary) -> {
                    peer.getModule(PeerModule.WEBRTC_PEER_TO_PEER_LISTENER, WebRtcPeerToPeerListenerModule.class)
                            .ifPresentOrElse(m -> m.onMessageReceived(data, isBinary), () -> {
                                if (isBinary) {
                                    peer.handleData(data);
                                }
                            });
                },
                // State handler
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {
                        log.info("WebRTC session connected for peer {}", peer.getRemoteId());
                        // Signal connected state
                        iceAsync.runAsync(false, "onWebRtcConnected", peer, () -> {
                            if (peer.getWebRtcSession() != webRtcSession || webRtcSession.isClosed()) {
                                log.debug(
                                        "Ignoring onConnected from stale/closed WebRtcSession for peer {}",
                                        peer.getRemoteId());
                                return;
                            }
                            peer.setIceState(CONNECTED);
                        });
                    }

                    @Override
                    public void onDisconnected() {
                        log.info("WebRTC session disconnected for peer {}", peer.getRemoteId());
                        iceAsync.runAsync(false, "onWebRtcDisconnected", peer, () -> {
                            if (peer.getWebRtcSession() != webRtcSession || webRtcSession.isClosed()) {
                                log.debug(
                                        "Ignoring onDisconnected from stale/closed WebRtcSession for peer {}",
                                        peer.getRemoteId());
                                return;
                            }
                            peer.lostConnect();
                            peer.setIceState(DISCONNECTED);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        log.error("WebRTC session error for peer {}: {}", peer.getRemoteId(), error);
                    }

                    @Override
                    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                        signalingService.onOfferCreated(sdp, candidates);
                    }

                    @Override
                    public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
                        signalingService.onAnswerCreated(sdp, candidates);
                    }

                    @Override
                    public void onRemoteDescriptionSet() {
                        signalingService.onRemoteDescriptionSet();
                    }

                    @Override
                    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                        signalingService.onIceCandidate(sdpMid, sdpMLineIndex, candidate);
                    }
                });

        // Store in Peer
        peer.setWebRtcSession(webRtcSession);
        peer.setWebRtcSignalingService(signalingService);

        log.info("WebRTC session created for peer {}", peer.getPeerIdentifier());
    }

    /**
     * Close WebRTC session for the peer.
     */
    protected void closeWebRtcSession(Peer peer) {
        WebRtcSession existing = peer.getWebRtcSession();
        if (existing != null) {
            existing.close();
            peer.setWebRtcSession(null);
        }
        WebRtcSignalingService existingSignaling = peer.getWebRtcSignalingService();
        if (existingSignaling != null) {
            existingSignaling.close();
            peer.setWebRtcSignalingService(null);
        }
    }

    /**
     * Gather candidates (WebRTC: create offer and send via RPC).
     */
    protected void gatherCandidates(Peer peer) {
        log.info("Gathering WebRTC candidates for peer {}", peer.getPeerIdentifier());

        WebRtcSignalingService signalingService = peer.getWebRtcSignalingService();
        if (signalingService == null) {
            log.error("WebRTC signaling service is null for peer {}", peer.getPeerIdentifier());
            connectLost(peer, true);
            return;
        }

        // Create offer (only offerer creates offer)
        if (peer.isLocalOffer()) {
            try {
                signalingService.createOffer();
                log.info("WebRTC offer created for peer {}", peer.getPeerIdentifier());
            } catch (Exception e) {
                log.error("Failed to create WebRTC offer for peer {}", peer.getPeerIdentifier(), e);
                connectLost(peer, true);
                return;
            }
        }

        // Send pending signaling via RPC
        flushSignalingViaRpc(peer);
    }

    /**
     * Flush pending signaling messages via RPC.
     */
    protected void flushSignalingViaRpc(Peer peer) {
        WebRtcSignalingService signalingService = peer.getWebRtcSignalingService();
        if (signalingService == null) {
            return;
        }

        // Get and send all pending messages
        WebRtcSignalingService.SignalingMessageSender sender = msg -> {
            // Send via RPC
            sendSignalingViaRpc(peer, msg);
        };
        signalingService.setSignalingMessageSender(sender);
        signalingService.flushPendingSignaling();
    }

    /**
     * Send a signaling message via RPC to the remote peer.
     */
    protected void sendSignalingViaRpc(Peer peer, CandidatesMessage message) {
        try {
            log.debug(
                    "Sending CandidatesMessage via RPC: ufrag={}, src={}, dst={}",
                    message.ufrag(),
                    message.srcId(),
                    message.destId());
            peer.sendToRpc(message);
        } catch (Exception e) {
            log.error("Failed to send CandidatesMessage via RPC", e);
        }
    }

    // ==================== State Handlers ====================

    protected void onDisconnected(Peer peer, IceState oldState) {
        log.info("ICE state disconnected");

        peer.setLastLostConnect(System.currentTimeMillis());
        closeWebRtcSession(peer);

        if (peer.isClosing()) {
            log.warn("Peer not connected anymore, aborting onConnectionLost of ICE");
            return;
        }

        if (iceGameSession.isGameEnded()) {
            log.warn("GAME ENDED, ABORTING onConnectionLost of ICE for peer ");
            return;
        }

        if (oldState == CONNECTED) {
            messageService.showMessage("Reconnecting to %s (connection lost)".formatted(peer.getRemoteLogin()));
        }
    }

    protected void connectLost(Peer peer, boolean force) {
        connectLost(peer, force, false);
    }

    protected void connectLost(Peer peer, boolean force, boolean clearIceState) {
        if (peer.getIceState() == DISCONNECTED && !clearIceState) {
            log.warn("Lost connection, albeit already in ice state disconnected");
            return;
        }
        long now = System.currentTimeMillis();
        long lastLostConnect = peer.getLastLostConnect();
        if (now - lastLostConnect < LOST_CONNECT_DURATION && !force) {
            log.debug(
                    "Skipping the lost connection, since the last connection loss was less than {}ms ago",
                    LOST_CONNECT_DURATION);
            return;
        }
        peer.setLastLostConnect(now);
        closeWebRtcSession(peer);
        log.info("Lost connection");

        peer.stopModules();

        peer.setConnected(false);

        peer.setIceState(clearIceState ? null : DISCONNECTED);
    }

    protected boolean checking(Peer peer) {
        log.debug("Checking WebRTC connection for peer {}", peer.getPeerIdentifier());

        WebRtcSession webRtcSession = peer.getWebRtcSession();
        if (webRtcSession == null) {
            log.error("WebRTC session is null for peer {}", peer.getPeerIdentifier());
            return false;
        }

        try {
            boolean connected = webRtcSession.waitForConnected(TIMEOUT_ON_CHECKING);
            if (connected) {
                log.info("WebRTC connection established for peer {}", peer.getPeerIdentifier());
            } else {
                log.warn("WebRTC connection timeout for peer {}", peer.getPeerIdentifier());
            }
            return connected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("WebRTC connection interrupted for peer {}", peer.getPeerIdentifier());
            return false;
        }
    }

    protected void onConnected(Peer peer) {
        log.info("ICE state connected (WebRTC)");

        WebRtcSession webRtcSession = peer.getWebRtcSession();
        if (webRtcSession == null) {
            log.warn("WebRTC session is null for peer {}", peer.getPeerIdentifier());
            connectLost(peer, true);
            return;
        }

        peer.setConnected(true);
        log.info("WebRTC connected, data channel ready for peer {}", peer.getPeerIdentifier());

        peer.startModules();
    }

    protected void asyncTimeoutAwaitingCandidates(int eventId, Peer peer) {
        LockUtil.executeWithLock(peer.getLock(LOCK_CONNECT), () -> {
            if (peer.isClosing()) {
                log.warn("Peer not connected anymore, aborting reinitiation of ICE");
                return;
            }
            int actualEventId = peer.getAwaitingCandidatesEventId().get();

            if (eventId != actualEventId) {
                return;
            }
            if (peer.getIceState() == AWAITING_CANDIDATES) {
                connectLost(peer, true);
            }
        });
    }
}
