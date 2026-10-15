package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests for WebRTC reconnection and resilience against late/duplicate CandidatesMessage.
 * Verifies:
 * 1. Initial connection with single unified CandidatesMessage (Vanilla ICE).
 * 2. Successful reconnect from controlling side (Peer A) and full data exchange after reconnect.
 * 3. Connection remains stable and does not drop 1-3 seconds after reconnect.
 * 4. Late duplicate Offer/Answer/Candidate messages do not break established connections.
 */
@Slf4j
@Tag("integration")
@DisplayName("WebRTC Reconnect Integration")
class WebRtcReconnectIntegrationTest extends WebRtcPeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 5;
    private static final long DATA_WAIT_MS = 2_000;

    @Override
    protected Set<PeerModule> getDisabledModules() {
        return Set.of(
                PeerModule.AUTO_RELAY_CALCULATE_RTT,
                PeerModule.CONNECTION_CHECKER_MODULE,
                PeerModule.AUTO_SETTING_ALLOW_CANDIDATE);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("WebRTC peers should reconnect cleanly from controlling side and exchange data without dropping")
    void testWebRtcReconnectFromControllingSide() throws IOException, InterruptedException {
        sleep(200);

        // Initial connection check
        assertTrue(peerA.isConnected(), "Peer A should be connected initially");
        assertTrue(peerB.isConnected(), "Peer B should be connected initially");

        // Round 1: Send data before reconnect
        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round1-A", NUM_PACKETS);
        sendPackets(socketB, "round1-B", NUM_PACKETS);
        sleep(DATA_WAIT_MS);

        assertEquals(NUM_PACKETS, socketB.getReceivedBytes().size(), "Socket B should receive round 1 packets from A");
        assertEquals(NUM_PACKETS, socketA.getReceivedBytes().size(), "Socket A should receive round 1 packets from B");

        // Trigger reconnect on controlling peer A
        log.info("Triggering reconnect on Peer A...");
        peerA.reconnect();

        // Wait for reconnection
        awaitReconnection(peerA, peerB);

        assertTrue(peerA.isConnected(), "Peer A should be connected after reconnect");
        assertTrue(peerB.isConnected(), "Peer B should be connected after reconnect");

        // Verify stability: wait 7 seconds to ensure no delayed disconnect happens (e.g. 5-second reinit timer or
        // 1-second drop bug)
        log.info("Waiting 7 seconds to verify post-reconnect connection stability...");
        sleep(7_000);
        assertTrue(peerA.isConnected(), "Peer A must stay connected 7 seconds after reconnect");
        assertTrue(peerB.isConnected(), "Peer B must stay connected 7 seconds after reconnect");

        // Round 2: Send data after reconnect
        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "round2-A", NUM_PACKETS);
        sendPackets(socketB, "round2-B", NUM_PACKETS);
        sleep(DATA_WAIT_MS);

        assertEquals(
                NUM_PACKETS,
                socketB.getReceivedBytes().size(),
                "Socket B should receive round 2 packets from A after reconnect");
        assertEquals(
                NUM_PACKETS,
                socketA.getReceivedBytes().size(),
                "Socket A should receive round 2 packets from B after reconnect");

        for (int i = 0; i < NUM_PACKETS; i++) {
            String fromB = new String(socketB.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("round2-A-" + i, fromB);
            String fromA = new String(socketA.getReceivedBytes().get(i), StandardCharsets.UTF_8);
            assertEquals("round2-B-" + i, fromA);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("WebRTC connection should survive late duplicate CandidatesMessages without disconnecting")
    void testLateCandidatesMessagesDoNotDropConnection() throws IOException, InterruptedException {
        sleep(200);

        assertTrue(peerA.isConnected(), "Peer A should be connected initially");
        assertTrue(peerB.isConnected(), "Peer B should be connected initially");

        // Simulate late duplicate messages arriving at peer A and peer B
        CandidatesMessage lateOffer = new CandidatesMessage(
                1, 2, "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n", "offer", List.of());
        CandidatesMessage lateAnswer = new CandidatesMessage(
                2, 1, "v=0\r\no=- 54321 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n", "answer", List.of());
        CandidatesMessage lateCandidate = new CandidatesMessage(
                1, 2, "candidate:1 1 udp 2113937151 127.0.0.1 50000 typ host", "candidate", List.of());

        log.info("Injecting late signaling messages into connected peers...");
        peerA.iceMessageFromRPC(lateAnswer);
        peerA.iceMessageFromRPC(lateCandidate);
        peerB.iceMessageFromRPC(lateOffer);
        peerB.iceMessageFromRPC(lateCandidate);

        // Sleep to let any potential incorrect state changes take effect
        sleep(2_000);

        // Assert peers are STILL connected
        assertTrue(peerA.isConnected(), "Peer A must still be connected after late messages");
        assertTrue(peerB.isConnected(), "Peer B must still be connected after late messages");

        // Verify data transmission is still functional
        socketA.clear();
        socketB.clear();
        sendPackets(socketA, "after-late-A", NUM_PACKETS);
        sendPackets(socketB, "after-late-B", NUM_PACKETS);
        sleep(DATA_WAIT_MS);

        assertEquals(
                NUM_PACKETS,
                socketB.getReceivedBytes().size(),
                "Socket B should still receive data after late messages");
        assertEquals(
                NUM_PACKETS,
                socketA.getReceivedBytes().size(),
                "Socket A should still receive data after late messages");
    }

    private void sendPackets(InMemoryDatagramSocket socket, String prefix, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            socket.sendString(prefix + "-" + i);
        }
    }

    private void awaitReconnection(Peer peerA, Peer peerB) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (peerA.isConnected() && peerB.isConnected()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Peers failed to reconnect within timeout (A=" + peerA.getIceState() + ", B=" + peerB.getIceState()
                + ", A.connected=" + peerA.isConnected() + ", B.connected=" + peerB.isConnected() + ")");
    }
}
