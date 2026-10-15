package com.faforever.iceadapter.ice.peer.modules.webrtc;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.fa.FaToPeerModule;
import com.faforever.iceadapter.ice.peer.modules.other.CommandModule;
import com.faforever.iceadapter.ice.peer.modules.other.PeerConnectivityCheckerModule;
import com.faforever.iceadapter.ice.peer.modules.relay.auto.RelayWebRtcPeerToPeerSenderModule;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;

/**
 * WebRTC-based peer-to-peer listener module.
 * Replaces PeerToPeerListenerModule when --transport=webrtc is used.
 * Receives data from WebRTC data channel and calls peer.handleData().
 */
@Slf4j
public class WebRtcPeerToPeerListenerModule implements ModuleBase, PeerEventListener {

    private final Peer peer;
    private volatile boolean running = false;
    private boolean enabled = true;

    public WebRtcPeerToPeerListenerModule(Peer peer) {
        this.peer = peer;
    }

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    /**
     * Called by WebRtcSession when a message is received from the data channel.
     */
    public void onMessageReceived(byte[] data, boolean isBinary) {
        if (!enabled || peer.isClosing()) {
            return;
        }

        if (isBinary) {
            // Binary data from data channel - treat as raw peer data
            handlerData(peer, data, data.length);
            return;
        }

        // Text data - could be signaling or control messages
        // For now, ignore text data (signaling goes through RPC)
        log.trace("Received text data from WebRTC data channel (ignored): {} bytes", data.length);
    }

    protected void handlerData(Peer peer, byte[] data, int length) {
        peer.setLastPacketReceived(System.currentTimeMillis());

        peer.handleData(data);

        // Log unknown packet types
        if (data[0] == FaToPeerModule.COMMAND_FA
                || data[0] == PeerConnectivityCheckerModule.COMMAND_ECHO
                || data[0] == CommandModule.COMMAND_BASE
                || data[0] == RelayWebRtcPeerToPeerSenderModule.COMMAND_AUTO_RELAY) {
            // Known protocol markers - silent
        } else if (DatagramSocketUtils.isStunPacket(data, length)) {
            int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            log.trace(
                    "STUN-like packet received via WebRTC, type: 0x{}, length: {}",
                    String.format("%04X", type),
                    length);
        } else {
            peer.getInvalidPacket().incrementAndGet();
            log.warn(
                    "Received invalid packet via WebRTC, first byte: 0x{}, length: {}, data (hex): {}",
                    String.format("%02X", data[0]),
                    length,
                    DatagramSocketUtils.bytesToHex(Arrays.copyOf(data, Math.min(length, 16))));
        }
    }

    @Override
    public void start() {
        running = true;
        log.info("WebRtcPeerToPeerListenerModule started for peer {}", peer.getPeerIdentifier());
    }

    @Override
    public void stop() {
        running = false;
        log.info("WebRtcPeerToPeerListenerModule stopped for peer {}", peer.getPeerIdentifier());
    }

    @Override
    public Boolean isRunning() {
        return running;
    }

    @Override
    public Boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }
}
