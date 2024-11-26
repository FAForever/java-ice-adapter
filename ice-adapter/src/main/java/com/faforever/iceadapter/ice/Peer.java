package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import java.io.IOException;
import java.net.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Getter
@Slf4j
public class Peer {

    private final GameSession gameSession;

    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;

    public volatile boolean closing = false;

    private final PeerIceModule ice = new PeerIceModule(this);
    private DatagramSocket faSocket; // Socket on which we are listening for FA / sending data to FA

    public Peer(GameSession gameSession, int remoteId, String remoteLogin, boolean localOffer, int preferredPort) {
        this.gameSession = gameSession;
        this.remoteId = remoteId;
        this.remoteLogin = remoteLogin;
        this.localOffer = localOffer;
        this.preferredPort = preferredPort;

        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}",
                getPeerIdentifier(),
                String.valueOf(localOffer),
                preferredPort);

        initForwarding(preferredPort);

        if (localOffer) {
            new Thread(ice::initiateIce).start();
        }
    }

    /**
     * Starts waiting for data from FA
     */
    private void initForwarding(int port) {
        try {
            faSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
        }

        new Thread(this::faListener).start();

        log.debug("Now forwarding data to peer {}", getPeerIdentifier());
    }

    /**
     * Forwards data received on ICE to FA
     * @param data
     * @param offset
     * @param length
     */
    synchronized void onIceDataReceived(byte data[], int offset, int length) {
        try {
            DatagramPacket packet =
                    new DatagramPacket(data, offset, length, InetAddress.getByName("127.0.0.1"), IceAdapter.LOBBY_PORT);
            faSocket.send(packet);
        } catch (UnknownHostException e) {
        } catch (IOException e) {
            if (closing) {
                log.debug("Ignoring error the send packet because the connection was closed {}", getPeerIdentifier());
            } else {
                log.error(
                        "Error while writing to local FA as peer (probably disconnecting from peer) {}",
                        getPeerIdentifier(),
                        e);
            }
        }
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {
        byte data[] = new byte
                [65536]; // 64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
        while (IceAdapter.running && IceAdapter.gameSession == gameSession) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length);
                faSocket.receive(packet);
                ice.onFaDataReceived(data, packet.getLength());
            } catch (IOException e) {
                if (closing) {
                    log.debug(
                            "Ignoring error the receive packet because the connection was closed as peer {}",
                            getPeerIdentifier());
                } else {
                    log.debug(
                            "Error while reading from local FA as peer (probably disconnecting from peer) {}",
                            getPeerIdentifier(),
                            e);
                }
                return;
            }
        }
        log.debug("No longer listening for messages from FA");
    }

    public void close() {
        if (closing) {
            return;
        }

        log.info("Closing peer for player {}", getPeerIdentifier());

        closing = true;
        if (faSocket != null) {
            faSocket.close();
        }

        ice.close();
    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return String.format("%s(%d)", this.remoteLogin, this.remoteId);
    }
}
