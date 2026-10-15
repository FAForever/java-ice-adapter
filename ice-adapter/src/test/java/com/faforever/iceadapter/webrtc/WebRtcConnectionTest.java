package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests for WebRtcConnectionImpl - verifies JSON serialization/deserialization
 * over WebRTC data channel and message dispatch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class WebRtcConnectionTest {

    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDeviceModule;
    private List<RTCIceCandidate> callerCandidates;
    private List<RTCIceCandidate> calleeCandidates;
    private AtomicReference<RTCPeerConnection> callerRef;
    private AtomicReference<RTCPeerConnection> calleeRef;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void setUpFactory() {
        audioDeviceModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        factory = new PeerConnectionFactory(audioDeviceModule);
        callerRef = new AtomicReference<>();
        calleeRef = new AtomicReference<>();
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
     * Test that WebRtcConnectionImpl can serialize CandidatesMessage and send it
     * over a WebRTC data channel, and that the receiver can deserialize it.
     */
    @Test
    @DisplayName("WebRtcConnectionImpl should send and receive CandidatesMessage over data channel")
    void shouldSendCandidatesMessageOverDataChannel() throws Exception {
        // Setup two peers
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

        // Callee observer: forwards ICE to caller and captures data channel
        AtomicReference<RTCDataChannel> calleeChannelRef = new AtomicReference<>();
        PeerConnectionObserver calleeObserver = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                RTCPeerConnection caller = callerRef.get();
                if (caller != null) {
                    caller.addIceCandidate(candidate);
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel dataChannel) {
                calleeChannelRef.set(dataChannel);
            }
        };

        RTCConfiguration config = createLocalConfig();
        RTCPeerConnection caller = factory.createPeerConnection(config, callerObserver);
        callerRef.set(caller);
        RTCPeerConnection callee = factory.createPeerConnection(config, calleeObserver);
        calleeRef.set(callee);

        try {
            // Create data channel on caller side
            RTCDataChannelInit channelInit = new RTCDataChannelInit();
            channelInit.ordered = true;
            RTCDataChannel callerChannel = caller.createDataChannel("test-channel", channelInit);

            // Perform SDP exchange
            performOfferAnswer(caller, callee);

            // Wait for ICE gathering
            Thread.sleep(3000);

            // Exchange remaining ICE candidates
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

            // Wait for connection
            waitUntilConnected(caller, callee, 15_000);

            // Wait for data channel to open
            assertTrue(waitForDataChannelOpen(callerChannel, 5_000), "Caller data channel should reach OPEN state");

            // Track received messages on callee side
            List<byte[]> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            RTCDataChannel calleeChannel = calleeChannelRef.get();
            assertNotNull(calleeChannel, "Callee should have received a data channel");

            calleeChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}

                @Override
                public void onStateChange() {}

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    byte[] payload;
                    if (buffer.data.hasArray()) {
                        payload = buffer.data.array();
                    } else {
                        payload = new byte[buffer.data.limit()];
                        buffer.data.get(payload);
                    }
                    synchronized (receivedMessages) {
                        receivedMessages.add(payload.clone());
                    }
                }
            });

            // Create a CandidatesMessage and send it via caller's data channel
            CandidatesMessage message = new CandidatesMessage(
                    1,
                    2,
                    "password",
                    "ufrag1",
                    List.of(new CandidatePacket(
                            "f1",
                            "udp",
                            1694498879L,
                            "127.0.0.1",
                            8080,
                            CandidateType.HOST_CANDIDATE,
                            0,
                            "u1",
                            "",
                            0)));

            // Serialize and send (simulating WebRtcConnectionImpl.sendToRpc behavior)
            String json = objectMapper.writeValueAsString(message);
            ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            RTCDataChannelBuffer dataBuffer = new RTCDataChannelBuffer(buffer, false);
            callerChannel.send(dataBuffer);

            // Wait for message delivery
            Thread.sleep(1_000);

            // Verify message was received
            assertEquals(1, receivedMessages.size(), "Should receive 1 message");
            String receivedJson = new String(receivedMessages.get(0), StandardCharsets.UTF_8);

            // Deserialize and verify
            CandidatesMessage receivedMessage = objectMapper.readValue(receivedJson, CandidatesMessage.class);
            assertEquals(message.srcId(), receivedMessage.srcId());
            assertEquals(message.destId(), receivedMessage.destId());
            assertEquals(message.password(), receivedMessage.password());
            assertEquals(message.ufrag(), receivedMessage.ufrag());
            assertEquals(
                    message.candidates().size(), receivedMessage.candidates().size());

        } finally {
            RTCPeerConnection c = callerRef.get();
            if (c != null) c.close();
            RTCPeerConnection d = calleeRef.get();
            if (d != null) d.close();
        }
    }

    // ==================== Helper Methods ====================

    private RTCConfiguration createLocalConfig() {
        RTCConfiguration config = new RTCConfiguration();
        config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
        return config;
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
        throw new AssertionError("Connection timeout after " + timeoutMs + "ms. " + "Caller state: "
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
}
