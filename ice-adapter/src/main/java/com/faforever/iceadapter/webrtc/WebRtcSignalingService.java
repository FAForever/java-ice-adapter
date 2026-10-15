package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages WebRTC signaling for a peer connection using standard {@link CandidatesMessage}.
 * Encodes SDP Offer/Answer and ICE candidates into the FAF server-compatible CandidatesMessage format.
 */
@Slf4j
public class WebRtcSignalingService {

    private final WebRtcSession webRtcSession;
    private final int localId;
    private final int remoteId;

    // Callback for sending signaling messages via RPC
    private volatile SignalingMessageSender signalingMessageSender;

    // Pending items to send
    private final ConcurrentHashMap<String, CandidatesMessage> pendingOffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CandidatesMessage> pendingAnswer = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface SignalingMessageSender {
        void sendSignalingMessage(CandidatesMessage message);
    }

    public WebRtcSignalingService(WebRtcSession webRtcSession, int localId, int remoteId) {
        this.webRtcSession = webRtcSession;
        this.localId = localId;
        this.remoteId = remoteId;
    }

    public void setSignalingMessageSender(SignalingMessageSender sender) {
        this.signalingMessageSender = sender;
        if (sender != null) {
            flushPendingSignaling();
        }
    }

    /**
     * Create an SDP offer.
     * The offer is sent via signalingMessageSender callback.
     */
    public void createOffer() {
        log.info("Creating WebRTC offer for peer {}", remoteId);
        webRtcSession.createOffer();
    }

    /**
     * Process a remote SDP offer received via signaling.
     */
    public void processRemoteOffer(String sdp, List<CandidatePacket> candidates) {
        log.info(
                "Processing remote SDP offer with {} candidates for peer {}",
                candidates != null ? candidates.size() : 0,
                remoteId);
        webRtcSession.processRemoteOffer(sdp, candidates);
    }

    public void processRemoteOffer(String sdp) {
        processRemoteOffer(sdp, List.of());
    }

    /**
     * Process a remote SDP answer received via signaling.
     */
    public void processRemoteAnswer(String sdp, List<CandidatePacket> candidates) {
        log.info(
                "Processing remote SDP answer with {} candidates for peer {}",
                candidates != null ? candidates.size() : 0,
                remoteId);
        webRtcSession.processRemoteAnswer(sdp, candidates);
    }

    public void processRemoteAnswer(String sdp) {
        processRemoteAnswer(sdp, List.of());
    }

    /**
     * Add a remote ICE candidate received via signaling.
     */
    public void addRemoteCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        log.debug("Adding remote ICE candidate for peer {}: {}:{}", remoteId, sdpMid, sdpMLineIndex);
        webRtcSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
    }

    /**
     * Flush all pending signaling messages via RPC.
     * Called by ConnectService after state transitions.
     */
    public void flushPendingSignaling() {
        if (signalingMessageSender == null) {
            return;
        }

        // Send offer
        pendingOffer.forEach((key, msg) -> {
            signalingMessageSender.sendSignalingMessage(msg);
            log.info("Sent SDP offer via RPC (CandidatesMessage) to peer {}", remoteId);
        });
        pendingOffer.clear();

        // Send answer
        pendingAnswer.forEach((key, msg) -> {
            signalingMessageSender.sendSignalingMessage(msg);
            log.info("Sent SDP answer via RPC (CandidatesMessage) to peer {}", remoteId);
        });
        pendingAnswer.clear();
    }

    /**
     * Called by WebRtcSession when an offer is created with gathered candidates.
     */
    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
        CandidatesMessage message =
                new CandidatesMessage(localId, remoteId, sdp, "offer", candidates != null ? candidates : List.of());
        if (signalingMessageSender != null) {
            signalingMessageSender.sendSignalingMessage(message);
            log.info(
                    "Sent unified SDP offer with {} candidates via RPC to peer {}",
                    message.candidates().size(),
                    remoteId);
        } else {
            pendingOffer.put("offer", message);
            log.info("Stored unified SDP offer for peer {}", remoteId);
        }
    }

    public void onOfferCreated(String sdp) {
        onOfferCreated(sdp, List.of());
    }

    /**
     * Called by WebRtcSession when an answer is created with gathered candidates.
     */
    public void onAnswerCreated(String sdp, List<CandidatePacket> candidates) {
        CandidatesMessage message =
                new CandidatesMessage(localId, remoteId, sdp, "answer", candidates != null ? candidates : List.of());
        if (signalingMessageSender != null) {
            signalingMessageSender.sendSignalingMessage(message);
            log.info(
                    "Sent unified SDP answer with {} candidates via RPC to peer {}",
                    message.candidates().size(),
                    remoteId);
        } else {
            pendingAnswer.put("answer", message);
            log.info("Stored unified SDP answer for peer {}", remoteId);
        }
    }

    public void onAnswerCreated(String sdp) {
        onAnswerCreated(sdp, List.of());
    }

    /**
     * Called by WebRtcSession when a remote description is set.
     */
    public void onRemoteDescriptionSet() {
        log.info("Remote description set for peer {}", remoteId);
    }

    /**
     * Called by WebRtcSession when an ICE candidate is gathered.
     */
    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        // Non-trickle Vanilla ICE: all candidates are included in the single offer/answer CandidatesMessage.
        // We do not send individual trickle ICE messages to avoid network races and reconnect drops.
        log.debug("Buffered candidate for peer {}: {}:{}", remoteId, sdpMid, candidate);
    }

    /**
     * Check if the session is connected.
     */
    public boolean isConnected() {
        return webRtcSession.isConnected();
    }

    /**
     * Close the session.
     */
    public void close() {
        webRtcSession.close();
        pendingOffer.clear();
        pendingAnswer.clear();
    }
}
