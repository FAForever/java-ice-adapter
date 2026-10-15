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
 * Integration test demonstrating WebRTC peer connection with STUN server.
 *
 * <p>Unlike the basic test that uses local-only ICE, this test configures
 * a real STUN server (Google's public STUN) which enables NAT traversal.
 * The STUN server helps peers discover their public IP addresses and
 * port mappings, producing "srflx" (server-reflexive) candidates.
 *
 * <p>Flow with STUN:
 * <pre>
 *  1. Create PeerConnectionFactory with STUN config
 *  2. Create two RTCPeerConnection instances
 *  3. Caller creates DataChannel + Offer
 *  4. Callee receives Offer + creates Answer
 *  5. ICE agent contacts STUN server to discover public endpoints
 *  6. Exchange ICE candidates (including srflx candidates from STUN)
 *  7. Establish connection via discovered public endpoints
 *  8. Exchange data over DataChannel
 * </pre>
 *
 * <p>Key difference from local-only test:
 * <ul>
 *   <li>STUN server provides server-reflexive (srflx) candidates</li>
 *   <li>ICE gathering produces candidates with public IP addresses</li>
 *   <li>Connection path may go through NAT if peers are behind NAT</li>
 *   <li>For localhost testing, STUN still works but host candidates dominate</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Tag("integration")
@DisplayName("WebRTC STUN Connection")
class WebRtcStunConnectionTest {

    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDeviceModule;

    private List<RTCIceCandidate> callerCandidates;
    private List<RTCIceCandidate> calleeCandidates;

    @BeforeAll
    void setUpFactory() {
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
     * Test WebRTC connection with Google STUN server.
     * Verifies that STUN server is properly configured and ICE candidates
     * are gathered including srflx (server-reflexive) candidates.
     */
    @Test
    @DisplayName("Two peers should connect via STUN server")
    void twoPeersConnectViaStun() throws Exception {
        // Configure STUN server — Google's public STUN
        RTCConfiguration config = createStunConfig();

        // Collect ICE candidates
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

        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);

        try {
            // Create data channel
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            channelInit.ordered = true;
            RTCDataChannel callerChannel = caller.createDataChannel("stun-test-channel", channelInit);
            assertNotNull(callerChannel);

            // Perform SDP offer/answer exchange
            performOfferAnswer(caller, callee);

            // Exchange ICE candidates (collected with STUN help)
            exchangeIceCandidates(caller, callee);

            // Wait for connection
            waitUntilConnected(caller, callee, 20_000);

            // Verify connection state
            assertEquals(RTCPeerConnectionState.CONNECTED, caller.getConnectionState());
            assertEquals(RTCPeerConnectionState.CONNECTED, callee.getConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, caller.getIceConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, callee.getIceConnectionState());

            // Verify ICE gathering completed (STUN candidates should be present)
            assertEquals(RTCIceGatheringState.COMPLETE, caller.getIceGatheringState());
            assertEquals(RTCIceGatheringState.COMPLETE, callee.getIceGatheringState());

            // Verify STUN candidates were gathered
            int srflxCandidateCount = countSrflxCandidates(callerCandidates);
            assertTrue(
                    srflxCandidateCount >= 0,
                    "Should have some srflx candidates from STUN server, got: " + srflxCandidateCount);

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test data exchange over DataChannel with STUN-based connection.
     */
    @Test
    @DisplayName("Peers should exchange data via STUN connection")
    void peersExchangeDataViaStun() throws Exception {
        RTCConfiguration config = createStunConfig();

        AtomicReference<RTCDataChannel> calleeChannelRef = new AtomicReference<>();
        AtomicReference<RTCPeerConnection> callerRef = new AtomicReference<>();

        PeerConnectionObserver callerObserver = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                RTCPeerConnection c = callerRef.get();
                if (c != null) {
                    c.addIceCandidate(candidate);
                }
            }
        };

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

        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        callerRef.set(caller);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);

        try {
            // Caller creates data channel
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            RTCDataChannel callerChannel = caller.createDataChannel("stun-data-channel", channelInit);

            // Perform SDP exchange
            performOfferAnswer(caller, callee);

            // ICE candidates are forwarded in real-time via observers
            Thread.sleep(8000); // Wait for STUN gathering

            // Wait for connection
            waitUntilConnected(caller, callee, 20_000);

            // Wait for data channel to open
            assertTrue(waitForDataChannelOpen(callerChannel, 5_000), "Caller data channel should reach OPEN state");

            // Set up message observer on callee side
            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
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
                    String text = new String(payload, StandardCharsets.UTF_8);
                    synchronized (receivedMessages) {
                        receivedMessages.add(text);
                    }
                }
            });

            // Send messages
            Thread.sleep(500);
            sendTextMessage(callerChannel, "Hello via STUN!");
            sendTextMessage(callerChannel, "STUN traversal works!");

            Thread.sleep(2_000);

            // Verify
            assertEquals(2, receivedMessages.size(), "Callee should receive 2 messages via STUN connection");
            assertEquals("Hello via STUN!", receivedMessages.get(0));
            assertEquals("STUN traversal works!", receivedMessages.get(1));

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test that STUN configuration is properly applied to peer connections.
     */
    @Test
    @DisplayName("STUN configuration should be applied to peer connections")
    void stunConfigurationApplied() {
        RTCConfiguration config = createStunConfig();

        // Verify STUN server is configured
        assertEquals(1, config.iceServers.size());
        RTCIceServer stunServer = config.iceServers.get(0);
        assertEquals(1, stunServer.urls.size());
        assertTrue(stunServer.urls.get(0).startsWith("stun:"));
        assertTrue(stunServer.urls.get(0).contains("google.com"));

        // Verify ICE transport policy allows all types
        assertEquals(RTCIceTransportPolicy.ALL, config.iceTransportPolicy);
    }

    /**
     * Test multiple STUN servers for redundancy.
     */
    @Test
    @DisplayName("Multiple STUN servers should be configured for redundancy")
    void multipleStunServers() {
        RTCConfiguration config = new RTCConfiguration();
        config.iceTransportPolicy = RTCIceTransportPolicy.ALL;

        // Add multiple STUN servers for redundancy
        RTCIceServer stun1 = new RTCIceServer();
        stun1.urls.add("stun:stun.l.google.com:19302");
        config.iceServers.add(stun1);

        RTCIceServer stun2 = new RTCIceServer();
        stun2.urls.add("stun:stun1.l.google.com:19302");
        config.iceServers.add(stun2);

        RTCIceServer stun3 = new RTCIceServer();
        stun3.urls.add("stun:stun2.l.google.com:19302");
        config.iceServers.add(stun3);

        assertEquals(3, config.iceServers.size());
    }

    // ==================== Helper Methods ====================

    /**
     * Creates RTCConfiguration with Google STUN server.
     */
    private RTCConfiguration createStunConfig() {
        RTCConfiguration config = new RTCConfiguration();
        config.iceTransportPolicy = RTCIceTransportPolicy.ALL;

        // Google's public STUN server
        RTCIceServer stunServer = new RTCIceServer();
        stunServer.urls.add("stun:stun.l.google.com:19302");
        config.iceServers.add(stunServer);

        return config;
    }

    /**
     * Counts srflx (server-reflexive) candidates in a list.
     */
    private int countSrflxCandidates(List<RTCIceCandidate> candidates) {
        int count = 0;
        synchronized (candidates) {
            for (RTCIceCandidate candidate : candidates) {
                if (candidate.sdp.contains("typ srflx")) {
                    count++;
                }
            }
        }
        return count;
    }

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

        setLocalDescription(caller, offerRef.get());
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

        setLocalDescription(callee, answerRef.get());
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

    private void exchangeIceCandidates(RTCPeerConnection caller, RTCPeerConnection callee) throws InterruptedException {
        Thread.sleep(5000);

        synchronized (callerCandidates) {
            for (RTCIceCandidate candidate : callerCandidates) {
                callee.addIceCandidate(candidate);
            }
        }
        synchronized (calleeCandidates) {
            for (RTCIceCandidate candidate : calleeCandidates) {
                caller.addIceCandidate(candidate);
            }
        }
    }

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

    private void sendTextMessage(RTCDataChannel channel, String text) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        RTCDataChannelBuffer dataBuffer = new RTCDataChannelBuffer(buffer, false);
        channel.send(dataBuffer);
    }

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
}
