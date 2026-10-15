package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests for WebRTC peer connection over InMemory sockets.
 * Verifies end-to-end data transmission:
 * InMemoryDatagramSocket (FA) <-> FASocketModule <-> WebRtcPeerToPeerSenderModule <-> RTCDataChannel
 * <-> WebRtcPeerToPeerListenerModule <-> PeerToFaModule <-> InMemoryDatagramSocket (FA).
 */
@Tag("integration")
@DisplayName("WebRTC Peer Connection Integration")
class WebRtcPeerConnectionIntegrationTest extends WebRtcPeerConnectionIntegrationBase {

    private static final int NUM_PACKETS = 10;
    private static final long DATA_WAIT_MS = 3_000;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Two WebRTC peers should connect and exchange data through real modules")
    void testTwoPeersConnectAndExchangeData() throws IOException {
        sleep(100); // Give modules time to settle

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketA.sendString("message-from-A-%d".formatted(i));
        }

        for (int i = 0; i < NUM_PACKETS; i++) {
            socketB.sendString("message-from-B-%d".formatted(i));
        }

        // Wait for delivery
        sleep(DATA_WAIT_MS);
        // Extra wait for network propagation
        sleep(1000);

        List<byte[]> receivedAtSocketB = socketB.getReceivedBytes();
        assertEquals(
                NUM_PACKETS,
                receivedAtSocketB.size(),
                "socketB should receive " + NUM_PACKETS + " packets from A (sent via peerA), got: "
                        + receivedAtSocketB.size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtSocketB.get(i), StandardCharsets.UTF_8);
            assertEquals("message-from-A-" + i, received, "Packet " + i + " content mismatch from A->B");
        }

        List<byte[]> receivedAtSocketA = socketA.getReceivedBytes();
        assertEquals(
                NUM_PACKETS,
                receivedAtSocketA.size(),
                "socketA should receive " + NUM_PACKETS + " packets from B (sent via peerB), got: "
                        + receivedAtSocketA.size());
        for (int i = 0; i < NUM_PACKETS; i++) {
            String received = new String(receivedAtSocketA.get(i), StandardCharsets.UTF_8);
            assertEquals("message-from-B-" + i, received, "Packet " + i + " content mismatch from B->A");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Two WebRTC peers should exchange binary game payloads of varying sizes")
    void testTwoPeersExchangeBinaryData() throws IOException {
        sleep(100);

        byte[] payload512 = new byte[512];
        Arrays.fill(payload512, (byte) 0xAA);

        byte[] payload1024 = new byte[1024];
        Arrays.fill(payload1024, (byte) 0xBB);

        socketA.sendBytes(payload512);
        socketB.sendBytes(payload1024);

        sleep(DATA_WAIT_MS);

        List<byte[]> receivedAtB = socketB.getReceivedBytes();
        assertEquals(1, receivedAtB.size(), "socketB should receive 1 binary packet from A");
        assertArrayEquals(payload512, receivedAtB.get(0), "Payload content mismatch from A->B");

        List<byte[]> receivedAtA = socketA.getReceivedBytes();
        assertEquals(1, receivedAtA.size(), "socketA should receive 1 binary packet from B");
        assertArrayEquals(payload1024, receivedAtA.get(0), "Payload content mismatch from B->A");
    }
}
