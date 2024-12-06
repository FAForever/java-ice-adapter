package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.util.LockUtil;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.SneakyThrows;
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
    private final DatagramSocket faSocket; // Socket on which we are listening for FA / sending data to FA
    private final Lock lockSocketSend = new ReentrantLock();

    public Peer(GameSession gameSession, int remoteId, String remoteLogin, boolean localOffer, int preferredPort) {
        this.gameSession = gameSession;
        this.remoteId = remoteId;
        this.remoteLogin = remoteLogin;
        this.localOffer = localOffer;
        this.preferredPort = preferredPort;

        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}", getPeerIdentifier(), localOffer, preferredPort);

        faSocket = initForwarding(preferredPort);

        CompletableFuture.runAsync(this::faListener, IceAdapter.getExecutor());

        if (localOffer) {
            CompletableFuture.runAsync(ice::initiateIce, IceAdapter.getExecutor());
        }
    }

    public int getLocalPort() {
        return faSocket.getLocalPort();
    }

    /**
     * Starts waiting for data from FA
     */
    @SneakyThrows(SocketException.class)
    private DatagramSocket initForwarding(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            log.debug("Now forwarding data to peer {}", getPeerIdentifier());
            return socket;
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
            throw e;
        }
    }

    /**
     * Forwards data received on ICE to FA
     * @param data
     * @param offset
     * @param length
     */
    void onIceDataReceived(byte[] data, int offset, int length) {
        LockUtil.executeWithLock(lockSocketSend, () -> {
            try {
                DatagramPacket packet = new DatagramPacket(
                        data, offset, length, InetAddress.getByName("127.0.0.1"), GPGNetServer.getLobbyPort());
                faSocket.send(packet);
            } catch (UnknownHostException e) {
            } catch (IOException e) {
                if (closing) {
                    log.debug(
                            "Ignoring error the send packet because the connection was closed {}", getPeerIdentifier());
                } else {
                    log.error(
                            "Error while writing to local FA as peer (probably disconnecting from peer) {}",
                            getPeerIdentifier(),
                            e);
                }
            }
        });
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {
        byte[] data = new byte
                [65536]; // 64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
        while (!Thread.currentThread().isInterrupted() && IceAdapter.getGameSession() == gameSession && !closing) {
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
        faSocket.close();

        ice.close();
    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return "%s(%d)".formatted(this.remoteLogin, this.remoteId);
    }
}
