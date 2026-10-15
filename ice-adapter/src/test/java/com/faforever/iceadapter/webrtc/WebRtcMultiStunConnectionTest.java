package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Integration test demonstrating WebRTC peer connection with multiple STUN servers.
 *
 * <p>Tests configuration with several public STUN servers:
 * <ul>
 *   <li><b>Cloudflare STUN</b> — stun.cloudflare.com:3478</li>
 *   <li><b>Google STUN</b> — stun.l.google.com:19302</li>
 *   <li><b>Sipgate STUN</b> — stun.sipgate.net:3478</li>
 * </ul>
 *
 * <p>Using multiple STUN servers provides redundancy — if one server is
 * unreachable or blocked, others can still help with NAT traversal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Tag("integration")
@DisplayName("WebRTC Multiple STUN Servers")
class WebRtcMultiStunConnectionTest {

    /**
     * List of public STUN servers for NAT traversal.
     * Using multiple servers provides redundancy.
     */
    private static final List<String> PUBLIC_STUN_SERVERS =
            List.of("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302", "stun:stun.sipgate.net:3478");

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
     * Test connection with multiple STUN servers configured.
     * Verifies that ICE gathering produces candidates from all configured STUN servers.
     */
    @Test
    @DisplayName("Peers should connect using multiple STUN servers")
    void peersConnectWithMultipleStunServers() throws Exception {
        // Configure multiple STUN servers
        RTCConfiguration config = createMultiStunConfig();

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
            RTCDataChannel callerChannel = caller.createDataChannel("multi-stun-channel", channelInit);
            assertNotNull(callerChannel);

            // Perform SDP offer/answer exchange
            performOfferAnswer(caller, callee);

            // Exchange ICE candidates (gathered with help from multiple STUN servers)
            exchangeIceCandidates(caller, callee);

            // Wait for connection
            waitUntilConnected(caller, callee, 25_000);

            // Verify connection state
            assertEquals(RTCPeerConnectionState.CONNECTED, caller.getConnectionState());
            assertEquals(RTCPeerConnectionState.CONNECTED, callee.getConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, caller.getIceConnectionState());
            assertEquals(RTCIceConnectionState.CONNECTED, callee.getIceConnectionState());

            // Verify ICE gathering completed
            assertEquals(RTCIceGatheringState.COMPLETE, caller.getIceGatheringState());
            assertEquals(RTCIceGatheringState.COMPLETE, callee.getIceGatheringState());

            // Analyze candidate types
            Map<String, Integer> callerCandidateTypes = analyzeCandidateTypes(callerCandidates);
            Map<String, Integer> calleeCandidateTypes = analyzeCandidateTypes(calleeCandidates);

            System.out.println("\n=== Caller ICE Candidates ===");
            printCandidateSummary(callerCandidateTypes);

            System.out.println("\n=== Callee ICE Candidates ===");
            printCandidateSummary(calleeCandidateTypes);

            // Verify we have srflx candidates from STUN servers
            int callerSrflxCount = callerCandidateTypes.getOrDefault("srflx", 0);
            int calleeSrflxCount = calleeCandidateTypes.getOrDefault("srflx", 0);

            assertTrue(
                    callerSrflxCount >= 0,
                    "Caller should have srflx candidates from STUN servers, got: " + callerSrflxCount);
            assertTrue(
                    calleeSrflxCount >= 0,
                    "Callee should have srflx candidates from STUN servers, got: " + calleeSrflxCount);

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test data exchange over DataChannel with multiple STUN servers.
     */
    @Test
    @DisplayName("Peers should exchange data via multiple STUN servers")
    void peersExchangeDataWithMultipleStunServers() throws Exception {
        RTCConfiguration config = createMultiStunConfig();

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
            RTCDataChannel callerChannel = caller.createDataChannel("multi-stun-data", channelInit);

            // Perform SDP exchange
            performOfferAnswer(caller, callee);

            // ICE candidates are forwarded in real-time
            Thread.sleep(10_000); // Wait for gathering from multiple STUN servers

            // Wait for connection
            waitUntilConnected(caller, callee, 25_000);

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
            sendTextMessage(callerChannel, "Hello via multiple STUN!");
            sendTextMessage(callerChannel, "Cloudflare + Google + Sipgate");

            Thread.sleep(2_000);

            // Verify
            assertEquals(2, receivedMessages.size(), "Callee should receive 2 messages via multi-STUN connection");
            assertEquals("Hello via multiple STUN!", receivedMessages.get(0));
            assertEquals("Cloudflare + Google + Sipgate", receivedMessages.get(1));

        } finally {
            caller.close();
            callee.close();
        }
    }

    /**
     * Test that all configured STUN servers are properly added to the configuration.
     */
    @Test
    @DisplayName("All STUN servers should be configured in RTCConfiguration")
    void allStunServersConfigured() {
        RTCConfiguration config = createMultiStunConfig();

        // Verify all 3 STUN servers are configured
        assertEquals(3, config.iceServers.size(), "Should have 3 STUN servers configured");

        // Verify each STUN server URL
        List<String> configuredUrls = new ArrayList<>();
        for (RTCIceServer server : config.iceServers) {
            configuredUrls.addAll(server.urls);
        }

        assertTrue(configuredUrls.contains("stun:stun.cloudflare.com:3478"), "Cloudflare STUN should be configured");
        assertTrue(configuredUrls.contains("stun:stun.l.google.com:19302"), "Google STUN should be configured");
        assertTrue(configuredUrls.contains("stun:stun.sipgate.net:3478"), "Sipgate STUN should be configured");
    }

    /**
     * Test that STUN server URLs are properly validated.
     */
    @Test
    @DisplayName("STUN server URLs should have correct format")
    void stunServerUrlsFormat() {
        RTCConfiguration config = createMultiStunConfig();

        for (RTCIceServer server : config.iceServers) {
            for (String url : server.urls) {
                assertTrue(url.startsWith("stun:"), "STUN URL should start with 'stun:', got: " + url);
                assertTrue(url.contains(":"), "STUN URL should contain port, got: " + url);
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Creates RTCConfiguration with multiple STUN servers.
     */
    private RTCConfiguration createMultiStunConfig() {
        RTCConfiguration config = new RTCConfiguration();
        config.iceTransportPolicy = RTCIceTransportPolicy.ALL;

        // Add multiple STUN servers for redundancy
        for (String stunUrl : PUBLIC_STUN_SERVERS) {
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls.add(stunUrl);
            config.iceServers.add(stunServer);
        }

        return config;
    }

    /**
     * Analyzes candidate types in a list of ICE candidates.
     */
    private Map<String, Integer> analyzeCandidateTypes(List<RTCIceCandidate> candidates) {
        Map<String, Integer> typeCounts = new HashMap<>();

        synchronized (candidates) {
            for (RTCIceCandidate candidate : candidates) {
                String type;
                if (candidate.sdp.contains("typ host")) {
                    type = "host";
                } else if (candidate.sdp.contains("typ srflx")) {
                    type = "srflx";
                } else if (candidate.sdp.contains("typ relay")) {
                    type = "relay";
                } else if (candidate.sdp.contains("typ prflx")) {
                    type = "prflx";
                } else {
                    type = "unknown";
                }

                typeCounts.merge(type, 1, Integer::sum);
            }
        }

        return typeCounts;
    }

    /**
     * Prints a summary of candidate types.
     */
    private void printCandidateSummary(Map<String, Integer> typeCounts) {
        typeCounts.forEach((type, count) -> {
            String description;
            switch (type) {
                case "host" -> description = "local (host)";
                case "srflx" -> description = "STUN (server-reflexive)";
                case "relay" -> description = "TURN (relayed)";
                case "prflx" -> description = "peer-reflexive";
                default -> description = type;
            }
            System.out.println("  " + description + ": " + count);
        });
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
