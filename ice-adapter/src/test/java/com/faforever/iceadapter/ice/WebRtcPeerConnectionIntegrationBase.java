package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.base.InMemoryRpcBus;
import com.faforever.iceadapter.ice.base.TestGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for WebRTC peer connection integration tests.
 * Sets up two game sessions communicating via WebRTC data channel over InMemory sockets.
 */
public abstract class WebRtcPeerConnectionIntegrationBase {

    protected static final String LOCAL_IP_ADDRESS = "127.0.0.1";
    protected static final long ICE_READY_TIMEOUT_MS = 30_000;
    protected static final long POLL_INTERVAL_MS = 50;

    protected InMemoryRpcBus bus;
    protected InMemoryDatagramSocket socketA;
    protected InMemoryDatagramSocket socketB;
    protected TestGameSession gameA;
    protected TestGameSession gameB;
    protected Peer peerA;
    protected Peer peerB;

    protected Set<PeerModule> getDisabledModules() {
        return Set.of(PeerModule.CONNECTION_CHECKER_MODULE);
    }

    @BeforeEach
    void setUp() throws SocketException, InterruptedException {
        bus = new InMemoryRpcBus();
        bus.start();

        socketA = new InMemoryDatagramSocket();
        socketB = new InMemoryDatagramSocket();

        IceOptions optionsA =
                new IceOptions(1, 0, "PlayerA", 0, 0, 0, false, false, false, 0, 0, 250.0, null, true, false, true);
        IceOptions optionsB =
                new IceOptions(2, 0, "PlayerB", 0, 0, 0, false, false, false, 0, 0, 250.0, null, true, false, true);

        gameA = new TestGameSession(bus, optionsA, getDisabledModules());
        gameA.setLobbyPort(socketA.getLocalPort());
        gameB = new TestGameSession(bus, optionsB, getDisabledModules());
        gameB.setLobbyPort(socketB.getLocalPort());

        // Create peers - A offers (controlling), B answers (controlled)
        gameA.connectToPeer("PlayerB", 2, true, 0, AllowCombination.ALL);
        gameB.connectToPeer("PlayerA", 1, false, 0, AllowCombination.ALL);

        peerA = gameA.getPeer(2).orElse(null);
        peerB = gameB.getPeer(1).orElse(null);

        assertNotNull(peerA, "Peer A should exist");
        assertNotNull(peerB, "Peer B should exist");

        // Register peers with the RPC bus for WebRTC signaling (offer/answer/candidates)
        bus.registerPeer(peerA.getFromId(), peerA);
        bus.registerPeer(peerB.getFromId(), peerB);

        sleep(100);
        try {
            socketA.connect(
                    InetAddress.getByName(LOCAL_IP_ADDRESS), peerA.getFaSocket().getLocalPort());
            assertTrue(socketA.isConnected(), "Socket A should be connected");
            socketB.connect(
                    InetAddress.getByName(LOCAL_IP_ADDRESS), peerB.getFaSocket().getLocalPort());
            assertTrue(socketB.isConnected(), "Socket B should be connected");

            socketA.start();
            socketB.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        awaitIceReady(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A should be connected, state=" + peerA.getIceState());
        assertTrue(peerB.isConnected(), "Peer B should be connected, state=" + peerB.getIceState());

        // Clear sockets AFTER WebRTC connection is ready to discard any initial setup packets
        socketA.clear();
        socketB.clear();
    }

    @AfterEach
    void tearDown() {
        if (gameA != null) {
            gameA.close();
        }
        if (gameB != null) {
            gameB.close();
        }
        if (bus != null) {
            bus.stop();
        }
        if (socketA != null) {
            socketA.close();
        }
        if (socketB != null) {
            socketB.close();
        }
    }

    /**
     * Waits for both peers to establish WebRTC connection and become connected.
     */
    protected void awaitIceReady(Peer peerA, Peer peerB) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ICE_READY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (peerA.isConnected() && peerB.isConnected()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        Assertions.fail("WebRTC connection timeout after " + ICE_READY_TIMEOUT_MS + "ms (A=" + peerA.getIceState()
                + ", B=" + peerB.getIceState() + ", A.connected=" + peerA.isConnected() + ", B.connected="
                + peerB.isConnected() + ")");
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
