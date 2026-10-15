package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.RTCDataChannelSendObserver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("WebRTC Loopback Data Transfer Test")
class WebRtcLoopbackDataTransferTest {

    private WebRtcSession callerSession;
    private WebRtcSession calleeSession;

    @AfterEach
    void tearDown() {
        if (callerSession != null) {
            callerSession.close();
        }
        if (calleeSession != null) {
            calleeSession.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Two WebRtcSessions should establish DataChannel and transfer packets bidirectionally without loss")
    void testBidirectionalDataTransfer() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        callerSession = new WebRtcSession(factory);
        calleeSession = new WebRtcSession(factory);

        List<byte[]> callerReceived = new CopyOnWriteArrayList<>();
        List<byte[]> calleeReceived = new CopyOnWriteArrayList<>();

        CountDownLatch callerConnectedLatch = new CountDownLatch(1);
        CountDownLatch calleeConnectedLatch = new CountDownLatch(1);

        WebRtcSession.SessionStateHandler callerHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                callerConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Caller error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {
                // Forward offer to callee
                calleeSession.processRemoteOffer(sdp);
            }

            @Override
            public void onAnswerCreated(String sdp) {}

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                // Forward candidate to callee
                calleeSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        WebRtcSession.SessionStateHandler calleeHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                calleeConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Callee error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {}

            @Override
            public void onAnswerCreated(String sdp) {
                // Forward answer to caller
                callerSession.processRemoteAnswer(sdp);
            }

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                // Forward candidate to caller
                callerSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        // Initialize sessions (no external STUN needed for loopback)
        callerSession.init(true, List.of(), (data, isBinary) -> callerReceived.add(data), callerHandler);
        calleeSession.init(false, List.of(), (data, isBinary) -> calleeReceived.add(data), calleeHandler);

        // Caller creates offer to kick off negotiation
        callerSession.createOffer();

        // Wait for both sides to connect
        assertTrue(callerConnectedLatch.await(15, TimeUnit.SECONDS), "Caller should connect within timeout");
        assertTrue(calleeConnectedLatch.await(15, TimeUnit.SECONDS), "Callee should connect within timeout");
        assertTrue(callerSession.isConnected(), "Caller session must be connected");
        assertTrue(calleeSession.isConnected(), "Callee session must be connected");

        // Prepare test packets
        int packetCount = 50;
        List<byte[]> callerPackets = new ArrayList<>();
        List<byte[]> calleePackets = new ArrayList<>();

        for (int i = 0; i < packetCount; i++) {
            byte[] p1 = new byte[64];
            p1[0] = 0x03; // Simulated COMMAND_FA
            p1[1] = (byte) i;
            Arrays.fill(p1, 2, 64, (byte) (i + 1));
            callerPackets.add(p1);

            byte[] p2 = new byte[48];
            p2[0] = 0x03;
            p2[1] = (byte) (i + 100);
            Arrays.fill(p2, 2, 48, (byte) (i + 50));
            calleePackets.add(p2);
        }

        // Send caller -> callee
        for (byte[] packet : callerPackets) {
            assertTrue(callerSession.sendDataAsync(packet, true), "caller sendDataAsync should succeed");
        }

        // Send callee -> caller
        for (byte[] packet : calleePackets) {
            assertTrue(calleeSession.sendDataAsync(packet, true), "callee sendDataAsync should succeed");
        }

        // Wait for all packets to be received asynchronously
        waitForPackets(calleeReceived::size, packetCount, 10000);
        waitForPackets(callerReceived::size, packetCount, 10000);

        assertEquals(packetCount, calleeReceived.size(), "Callee should receive all packets from caller");
        assertEquals(packetCount, callerReceived.size(), "Caller should receive all packets from callee");

        // Verify order and payload integrity
        for (int i = 0; i < packetCount; i++) {
            assertArrayEquals(callerPackets.get(i), calleeReceived.get(i), "Callee packet " + i + " mismatch");
            assertArrayEquals(calleePackets.get(i), callerReceived.get(i), "Caller packet " + i + " mismatch");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Two WebRtcSessions should transfer data using sendDataAsync with RTCDataChannelSendObserver")
    void testAsyncDataTransferWithObserver() throws Exception {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory.getFactory());

        callerSession = new WebRtcSession(factory);
        calleeSession = new WebRtcSession(factory);

        CountDownLatch callerConnectedLatch = new CountDownLatch(1);
        CountDownLatch calleeConnectedLatch = new CountDownLatch(1);

        WebRtcSession.SessionStateHandler callerHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                callerConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Caller error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {
                calleeSession.processRemoteOffer(sdp);
            }

            @Override
            public void onAnswerCreated(String sdp) {}

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                calleeSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        WebRtcSession.SessionStateHandler calleeHandler = new WebRtcSession.SessionStateHandler() {
            @Override
            public void onConnected() {
                calleeConnectedLatch.countDown();
            }

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {
                fail("Callee error: " + error);
            }

            @Override
            public void onOfferCreated(String sdp) {}

            @Override
            public void onAnswerCreated(String sdp) {
                callerSession.processRemoteAnswer(sdp);
            }

            @Override
            public void onRemoteDescriptionSet() {}

            @Override
            public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
                callerSession.addRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
            }
        };

        CountDownLatch receiveLatch = new CountDownLatch(1);
        byte[] testData = new byte[] {1, 2, 3, 4, 5};

        callerSession.init(true, List.of(), (data, isBinary) -> {}, callerHandler);
        calleeSession.init(
                false,
                List.of(),
                (data, isBinary) -> {
                    if (Arrays.equals(testData, data)) {
                        receiveLatch.countDown();
                    }
                },
                calleeHandler);

        callerSession.createOffer();

        assertTrue(callerConnectedLatch.await(15, TimeUnit.SECONDS), "Caller should connect within timeout");
        assertTrue(calleeConnectedLatch.await(15, TimeUnit.SECONDS), "Callee should connect within timeout");

        CountDownLatch successLatch = new CountDownLatch(1);

        boolean accepted = callerSession.sendDataAsync(testData, true, new RTCDataChannelSendObserver() {
            @Override
            public void onSuccess() {
                successLatch.countDown();
            }

            @Override
            public void onFailure(String error) {
                fail("Send failed: " + error);
            }
        });

        assertTrue(accepted, "sendDataAsync should return true");
        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Send observer onSuccess should be called");
        assertTrue(receiveLatch.await(5, TimeUnit.SECONDS), "Callee should receive the packet");
    }

    private void waitForPackets(IntSupplier countSupplier, int target, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (countSupplier.getAsInt() >= target) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
