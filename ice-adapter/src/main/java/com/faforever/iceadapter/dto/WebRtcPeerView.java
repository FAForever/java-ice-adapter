package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import java.util.Locale;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@Data
public class WebRtcPeerView {
    private final IntegerProperty peerId = new SimpleIntegerProperty(-1);
    private final StringProperty login = new SimpleStringProperty("-");

    // Connection & States
    private final StringProperty peerConnectionState = new SimpleStringProperty("-");
    private final StringProperty iceConnectionState = new SimpleStringProperty("-");
    private final StringProperty dtlsState = new SimpleStringProperty("-");
    private final StringProperty dataChannelState = new SimpleStringProperty("-");
    private final StringProperty selectedPairState = new SimpleStringProperty("-");
    private final StringProperty nominated = new SimpleStringProperty("-");

    // Candidates
    private final StringProperty localCandidateType = new SimpleStringProperty("-");
    private final StringProperty remoteCandidateType = new SimpleStringProperty("-");
    private final StringProperty localAddress = new SimpleStringProperty("-");
    private final StringProperty remoteAddress = new SimpleStringProperty("-");

    // Latency & Bitrate
    private final StringProperty rttMs = new SimpleStringProperty("-");
    private final StringProperty echoRttMs = new SimpleStringProperty("-");
    private final StringProperty availableOutgoingBitrate = new SimpleStringProperty("-");
    private final StringProperty availableIncomingBitrate = new SimpleStringProperty("-");

    // Traffic / DataChannel
    private final StringProperty dataChannelLabel = new SimpleStringProperty("-");
    private final StringProperty messagesSent = new SimpleStringProperty("-");
    private final StringProperty messagesReceived = new SimpleStringProperty("-");
    private final StringProperty bytesSent = new SimpleStringProperty("-");
    private final StringProperty bytesReceived = new SimpleStringProperty("-");

    // Transport Packets
    private final StringProperty packetsSent = new SimpleStringProperty("-");
    private final StringProperty packetsReceived = new SimpleStringProperty("-");
    private final StringProperty packetsDiscarded = new SimpleStringProperty("-");

    public WebRtcPeerView(int peerId, String login) {
        this.peerId.set(peerId);
        this.login.set(login);
    }

    /**
     * Updates this view's properties from the peer's current WebRTC statistics.
     */
    public void update(Peer peer) {
        update(peer, true);
    }

    /**
     * Updates this view's properties from the peer's current WebRTC statistics,
     * conditionally populating candidate IP addresses based on showIpAddresses.
     */
    public void update(Peer peer, boolean showIpAddresses) {
        WebRtcSession session = peer.getWebRtcSession();
        if (session != null) {
            WebRtcSession.SessionStats stats = session.getStats();
            peerConnectionState.set(stats.getPeerConnectionState());
            iceConnectionState.set(stats.getIceConnectionState());
            dtlsState.set(stats.getDtlsState());
            dataChannelState.set(stats.getDataChannelState());
            selectedPairState.set(stats.getCandidatePairState());
            nominated.set(stats.isNominated() ? "Yes" : "No");

            localCandidateType.set(stats.getLocalCandidateType());
            remoteCandidateType.set(stats.getRemoteCandidateType());
            if (showIpAddresses) {
                localAddress.set(stats.getLocalAddress().isEmpty() ? "-" : stats.getLocalAddress());
                remoteAddress.set(stats.getRemoteAddress().isEmpty() ? "-" : stats.getRemoteAddress());
            } else {
                localAddress.set("-");
                remoteAddress.set("-");
            }

            rttMs.set(String.format(Locale.US, "%.1f", stats.getRttMs()));
            echoRttMs.set(String.format(Locale.US, "%.1f", peer.getEchoRtt()));

            if (stats.getAvailableOutgoingBitrate() > 0) {
                availableOutgoingBitrate.set(
                        String.format(Locale.US, "%.1f kbps", stats.getAvailableOutgoingBitrate() / 1000.0));
            } else {
                availableOutgoingBitrate.set("-");
            }
            if (stats.getAvailableIncomingBitrate() > 0) {
                availableIncomingBitrate.set(
                        String.format(Locale.US, "%.1f kbps", stats.getAvailableIncomingBitrate() / 1000.0));
            } else {
                availableIncomingBitrate.set("-");
            }

            dataChannelLabel.set(stats.getDataChannelLabel());
            messagesSent.set(String.valueOf(stats.getMessagesSent()));
            messagesReceived.set(String.valueOf(stats.getMessagesReceived()));
            bytesSent.set(formatBytes(stats.getBytesSent()));
            bytesReceived.set(formatBytes(stats.getBytesReceived()));

            packetsSent.set(String.valueOf(stats.getPacketsSent()));
            packetsReceived.set(String.valueOf(stats.getPacketsReceived()));
            packetsDiscarded.set(String.valueOf(stats.getPacketsDiscardedOnSend()));
        } else {
            peerConnectionState.set("No WebRTC");
            iceConnectionState.set("-");
            dtlsState.set("-");
            dataChannelState.set("-");
            selectedPairState.set("-");
            nominated.set("-");
            localCandidateType.set("-");
            remoteCandidateType.set("-");
            localAddress.set("-");
            remoteAddress.set("-");
            rttMs.set("-");
            echoRttMs.set(String.format(Locale.US, "%.1f", peer.getEchoRtt()));
            availableOutgoingBitrate.set("-");
            availableIncomingBitrate.set("-");
            dataChannelLabel.set("-");
            messagesSent.set("-");
            messagesReceived.set("-");
            bytesSent.set("-");
            bytesReceived.set("-");
            packetsSent.set("-");
            packetsReceived.set("-");
            packetsDiscarded.set("-");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
