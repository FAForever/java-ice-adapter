package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Integration test demonstrating two WebRTC peers connecting to each other
 * using in-memory signaling (SDP exchange + ICE candidate exchange) and
 * exchanging data over RTCDataChannel.
 *
 * <p>Flow:
 * <pre>
 *   1. Create PeerConnectionFactory
 *   2. Create two RTCPeerConnection instances (caller &amp; callee)
 *   3. Caller creates DataChannel + creates Offer
 *   4. Callee sets RemoteDescription(offer) + creates Answer
 *   5. Caller sets RemoteDescription(answer)
 *   6. Exchange ICE candidates between peers
 *   7. Wait for connection established
 *   8. Send/receive messages over DataChannel
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Tag("integration")
@DisplayName("WebRTC Peer Connection")
class WebRtcPeerConnectionTest {

    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDeviceModule;
    private List<RTCIceCandidate> callerCandidates;
    private List<RTCIceCandidate> calleeCandidates;

    @BeforeAll
    void setUpFactory() {
        // Use dummy audio device for headless test environment
        audioDeviceModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        factory = new PeerConnectionFactory(audioDeviceModule);
    }

    @AfterAll
    void tearDownFactory() {
        if (audioDeviceModule != null) {
            audioDeviceModule.dispose();
        }
        if (factory != null) {
            factory.dispose();
        }
    }

    /**
     * Basic test: two peers create a WebRTC connection and establish it.
     * Uses local-only ICE (no STUN/TURN) since both peers are in-process.
     */
    @Test
    @DisplayName("Two peers should connect via SDP offer/answer exchange")
    void twoPeersShouldConnect() throws Exception {
        // Collect ICE candidates and forward them to the remote peer
        callerCandidates = Collections.synchronizedList(new ArrayList<>());
        calleeCandidates = Collections.synchronizedList(new ArrayList<>());

        PeerConnectionObserver callerObserver = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                synchronized (callerCandidates) {
                    callerCandidates.add(candidate);
                }
            }
        };
        PeerConnectionObserver calleeObserver = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                synchronized (calleeCandidates) {
                    calleeCandidates.add(candidate);
                }
            }
        };

        RTCConfiguration config = createLocalConfig();

        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);

        try {
            // Step 1: Caller creates a data channel
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            channelInit.ordered = true;
            RTCDataChannel callerChannel = caller.createDataChannel("test-channel", channelInit);
            assertNotNull(callerChannel, "Caller data channel should not be null");

            // Step 2: Caller creates an offer
            AtomicReference<RTCSessionDescription> offerRef = new AtomicReference<>();
            CountDownLatch offerLatch = new CountDownLatch(1);
            caller.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    offerRef.set(description);
                    offerLatch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    fail("Create offer failed: " + error);
                    offerLatch.countDown();
                }
            });
            assertTrue(offerLatch.await(10, TimeUnit.SECONDS), "Offer creation should complete");
            RTCSessionDescription offer = offerRef.get();
            assertNotNull(offer, "Offer should not be null");
            assertEquals(RTCSdpType.OFFER, offer.sdpType);

            // Step 3: Caller sets local description
            setLocalDescription(caller, offer);

            // Step 4: Callee sets remote description (the offer)
            setRemoteDescription(callee, offer);

            // Step 5: Callee creates an answer
            AtomicReference<RTCSessionDescription> answerRef = new AtomicReference<>();
            CountDownLatch answerLatch = new CountDownLatch(1);
            callee.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    answerRef.set(description);
                    answerLatch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    fail("Create answer failed: " + error);
                    answerLatch.countDown();
                }
            });
            assertTrue(answerLatch.await(10, TimeUnit.SECONDS), "Answer creation should complete");
            RTCSessionDescription answer = answerRef.get();
            assertNotNull(answer, "Answer should not be null");
            assertEquals(RTCSdpType.ANSWER, answer.sdpType);

            // Step 6: Callee sets local description
            setLocalDescription(callee, answer);

            // Step 7: Caller sets remote description (the answer)
            setRemoteDescription(caller, answer);

            // Step 8: Exchange ICE candidates
            exchangeIceCandidates(caller, callee, callerCandidates, calleeCandidates);

            // Wait for ICE gathering and connection
            waitUntilConnected(caller, callee, 15_000);

            // Verify connection state
            assertEquals(RTCPeerConnectionState.CONNECTED, caller.getConnectionState());
            assertEquals(RTCPeerConnectionState.CONNECTED, callee.getConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, caller.getIceConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, callee.getIceConnectionState());
            assertEquals(RTCIceGatheringState.COMPLETE, caller.getIceGatheringState());
            assertEquals(RTCIceGatheringState.COMPLETE, callee.getIceGatheringState());

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test data exchange over RTCDataChannel between two connected peers.
     */
    @Test
    @DisplayName("Peers should exchange text messages over DataChannel")
    void peersShouldExchangeTextMessagesOverDataChannel() throws Exception {
        AtomicReference<RTCDataChannel> calleeChannelRef = new AtomicReference<>();
        AtomicReference<RTCPeerConnection> callerRef = new AtomicReference<>();

        TestPeerConnectionObserver callerObserver = new TestPeerConnectionObserver();

        // Callee observer: captures data channel + forwards ICE to caller
        PeerConnectionObserver calleeObserver = new PeerConnectionObserver() {
            @Override
            public void onDataChannel(RTCDataChannel dataChannel) {
                calleeChannelRef.set(dataChannel);
            }

            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                RTCPeerConnection c = callerRef.get();
                if (c != null) {
                    c.addIceCandidate(candidate);
                }
            }
        };

        RTCConfiguration config = createLocalConfig();

        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        callerRef.set(caller);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);

        try {
            // Caller creates data channel
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            RTCDataChannel callerChannel = caller.createDataChannel("data-channel", channelInit);

            // Perform SDP exchange
            performOfferAnswer(caller, callee);

            // Exchange ICE candidates
            exchangeIceCandidates(caller, callee);

            // Wait for connection
            waitUntilConnected(caller, callee, 15_000);

            assertEquals(RTCPeerConnectionState.CONNECTED, caller.getConnectionState());
            assertEquals(RTCPeerConnectionState.CONNECTED, callee.getConnectionState());

            // Wait for data channel to be open on caller side
            assertTrue(waitForDataChannelOpen(callerChannel, 5_000), "Caller data channel should reach OPEN state");

            // Set up message observer on callee side
            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            RTCDataChannel calleeChannel = calleeChannelRef.get();
            assertNotNull(calleeChannel, "Callee should have received a data channel");

            calleeChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // ignore
                }

                @Override
                public void onStateChange() {
                    // ignore
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    byte[] payload = extractBytes(buffer.data);
                    String text = new String(payload, StandardCharsets.UTF_8);
                    synchronized (receivedMessages) {
                        receivedMessages.add(text);
                    }
                }
            });

            // Send messages from caller to callee
            Thread.sleep(500); // Give time for channel to stabilize
            sendTextMessage(callerChannel, "Hello from caller");
            sendTextMessage(callerChannel, "WebRTC is awesome");

            Thread.sleep(1_000); // Wait for delivery

            // Verify callee received messages
            assertEquals(2, receivedMessages.size(), "Callee should receive 2 messages from caller");
            assertEquals("Hello from caller", receivedMessages.get(0));
            assertEquals("WebRTC is awesome", receivedMessages.get(1));

            // Send messages from callee to caller
            List<String> callerReceivedMessages = Collections.synchronizedList(new ArrayList<>());
            callerChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // ignore
                }

                @Override
                public void onStateChange() {
                    // ignore
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    byte[] payload = extractBytes(buffer.data);
                    String text = new String(payload, StandardCharsets.UTF_8);
                    synchronized (callerReceivedMessages) {
                        callerReceivedMessages.add(text);
                    }
                }
            });

            sendTextMessage(calleeChannel, "Hello from callee");
            sendTextMessage(calleeChannel, "Bidirectional works!");

            Thread.sleep(1_000);

            assertEquals(2, callerReceivedMessages.size(), "Caller should receive 2 messages from callee");
            assertEquals("Hello from callee", callerReceivedMessages.get(0));
            assertEquals("Bidirectional works!", callerReceivedMessages.get(1));

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test binary data exchange over RTCDataChannel.
     */
    @Test
    @DisplayName("Peers should exchange binary data over DataChannel")
    void peersShouldExchangeBinaryDataOverDataChannel() throws Exception {
        AtomicReference<RTCDataChannel> calleeChannelRef = new AtomicReference<>();
        AtomicReference<RTCPeerConnection> callerRef = new AtomicReference<>();

        TestPeerConnectionObserver callerObserver = new TestPeerConnectionObserver();

        // Callee observer: captures data channel + forwards ICE to caller
        PeerConnectionObserver calleeObserver = new PeerConnectionObserver() {
            @Override
            public void onDataChannel(RTCDataChannel dataChannel) {
                calleeChannelRef.set(dataChannel);
            }

            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                RTCPeerConnection c = callerRef.get();
                if (c != null) {
                    c.addIceCandidate(candidate);
                }
            }
        };

        RTCConfiguration config = createLocalConfig();

        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        callerRef.set(caller);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);

        try {
            // Caller creates data channel
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            RTCDataChannel callerChannel = caller.createDataChannel("binary-channel", channelInit);

            // Perform SDP exchange and ICE candidate exchange
            performOfferAnswer(caller, callee);
            exchangeIceCandidates(caller, callee);

            // Wait for connection
            waitUntilConnected(caller, callee, 15_000);

            // Wait for data channel to open
            assertTrue(waitForDataChannelOpen(callerChannel, 5_000));

            // Set up binary message observer on callee
            List<byte[]> receivedBinary = Collections.synchronizedList(new ArrayList<>());
            RTCDataChannel calleeChannel = calleeChannelRef.get();
            assertNotNull(calleeChannel);

            calleeChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // ignore
                }

                @Override
                public void onStateChange() {
                    // ignore
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    byte[] payload = extractBytes(buffer.data);
                    synchronized (receivedBinary) {
                        receivedBinary.add(payload);
                    }
                }
            });

            // Send binary data
            Thread.sleep(500);
            byte[] binaryData1 = new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
            byte[] binaryData2 = new byte[1024];
            for (int i = 0; i < 1024; i++) {
                binaryData2[i] = (byte) (i & 0xFF);
            }

            sendBinaryMessage(callerChannel, binaryData1);
            sendBinaryMessage(callerChannel, binaryData2);

            Thread.sleep(1_000);

            // Verify received binary data
            assertEquals(2, receivedBinary.size());
            assertByteArrayEquals(binaryData1, receivedBinary.get(0));
            assertByteArrayEquals(binaryData2, receivedBinary.get(1));

        } finally {
            caller.close();
            callee.close();
        }
    }

    // ==================== Helper Methods ====================

    private RTCConfiguration createLocalConfig() {
        RTCConfiguration config = new RTCConfiguration();
        // Use ALL policy to allow host candidates (needed for in-process connection)
        config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
        return config;
    }

    /**
     * Performs the SDP offer/answer exchange between caller and callee.
     */
    private void performOfferAnswer(RTCPeerConnection caller, RTCPeerConnection callee) throws Exception {
        // Caller creates offer
        AtomicReference<RTCSessionDescription> offerRef = new AtomicReference<>();
        CountDownLatch offerLatch = new CountDownLatch(1);
        caller.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription desc) {
                offerRef.set(desc);
                offerLatch.countDown();
            }

            @Override
            public void onFailure(String error) {
                fail("Create offer failed: " + error);
                offerLatch.countDown();
            }
        });
        assertTrue(offerLatch.await(10, TimeUnit.SECONDS));

        // Caller sets local description
        setLocalDescription(caller, offerRef.get());

        // Callee sets remote description
        setRemoteDescription(callee, offerRef.get());

        // Callee creates answer
        AtomicReference<RTCSessionDescription> answerRef = new AtomicReference<>();
        CountDownLatch answerLatch = new CountDownLatch(1);
        callee.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription desc) {
                answerRef.set(desc);
                answerLatch.countDown();
            }

            @Override
            public void onFailure(String error) {
                fail("Create answer failed: " + error);
                answerLatch.countDown();
            }
        });
        assertTrue(answerLatch.await(10, TimeUnit.SECONDS));

        // Callee sets local description
        setLocalDescription(callee, answerRef.get());

        // Caller sets remote description
        setRemoteDescription(caller, answerRef.get());
    }

    private void setLocalDescription(RTCPeerConnection peer, RTCSessionDescription description) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        peer.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(String error) {
                errorRef.set(new RuntimeException(error));
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
    }

    private void setRemoteDescription(RTCPeerConnection peer, RTCSessionDescription description) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        peer.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(String error) {
                errorRef.set(new RuntimeException(error));
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
    }

    /**
     * Exchanges ICE candidates between two peers using pre-collected lists.
     */
    private void exchangeIceCandidates(
            RTCPeerConnection caller,
            RTCPeerConnection callee,
            List<RTCIceCandidate> callerCands,
            List<RTCIceCandidate> calleeCands)
            throws InterruptedException {
        // Wait for ICE gathering to complete
        Thread.sleep(5000); // Allow time for candidate gathering

        // Exchange candidates
        synchronized (callerCands) {
            for (RTCIceCandidate candidate : callerCands) {
                callee.addIceCandidate(candidate);
            }
        }
        synchronized (calleeCands) {
            for (RTCIceCandidate candidate : calleeCands) {
                caller.addIceCandidate(candidate);
            }
        }
    }

    /**
     * Waits for ICE gathering to complete (candidates already forwarded in real-time via observers).
     */
    private void exchangeIceCandidates(RTCPeerConnection caller, RTCPeerConnection callee) throws InterruptedException {
        // Candidates are already forwarded in real-time via calleeObserver.onIceCandidate()
        // Just wait for gathering to complete
        Thread.sleep(5000);
    }

    /**
     * Waits until both peers are connected or timeout is reached.
     */
    private void waitUntilConnected(RTCPeerConnection caller, RTCPeerConnection callee, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (caller.getConnectionState() == RTCPeerConnectionState.CONNECTED
                    && callee.getConnectionState() == RTCPeerConnectionState.CONNECTED) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Connection timeout after " + timeoutMs + "ms. " + "Caller state: "
                + caller.getConnectionState() + ", " + "Callee state: "
                + callee.getConnectionState());
    }

    /**
     * Waits for a data channel to reach OPEN state.
     */
    private boolean waitForDataChannelOpen(RTCDataChannel channel, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (channel.getState() == RTCDataChannelState.OPEN) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * Sends a text message over a data channel.
     */
    private void sendTextMessage(RTCDataChannel channel, String text) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        RTCDataChannelBuffer dataBuffer = new RTCDataChannelBuffer(buffer, false);
        channel.send(dataBuffer);
    }

    /**
     * Sends a binary message over a data channel.
     */
    private void sendBinaryMessage(RTCDataChannel channel, byte[] data) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        RTCDataChannelBuffer dataBuffer = new RTCDataChannelBuffer(buffer, true);
        channel.send(dataBuffer);
    }

    /**
     * Extracts bytes from a ByteBuffer into a byte array.
     */
    private byte[] extractBytes(ByteBuffer buffer) {
        byte[] payload;
        if (buffer.hasArray()) {
            payload = buffer.array();
        } else {
            payload = new byte[buffer.limit()];
            buffer.get(payload);
        }
        return payload;
    }

    /**
     * Asserts that two byte arrays are equal.
     */
    private void assertByteArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "Byte array length mismatch");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "Byte mismatch at index " + i);
        }
    }

    // ==================== Observer Implementations ====================

    /**
     * Minimal observer that does nothing by default.
     * ICE candidates are collected separately by other observers.
     */
    private static class TestPeerConnectionObserver implements PeerConnectionObserver {
        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            // In this test, ICE candidates are forwarded by dedicated observers
        }
    }
}
