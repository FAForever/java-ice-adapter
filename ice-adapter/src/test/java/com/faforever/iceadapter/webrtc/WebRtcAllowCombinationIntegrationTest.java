package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebRtcSession Offer/Answer Gathering with AllowCombination")
class WebRtcAllowCombinationIntegrationTest {

    private WebRtcConnectionFactory factory;
    private WebRtcSession session;

    @BeforeEach
    void setUp() {
        factory = WebRtcConnectionFactory.getInstance();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    @DisplayName("createOffer with REFLEXIVE_RELAY should not emit any HOST candidates in SDP or candidate list")
    void testOfferWithReflexiveRelayGathersNoHostCandidates() throws Exception {
        session = new WebRtcSession(factory);
        CountDownLatch offerLatch = new CountDownLatch(1);
        AtomicReference<String> offerSdpRef = new AtomicReference<>();
        List<CandidatePacket> gatheredCandidates = Collections.synchronizedList(new ArrayList<>());

        session.init(
                true,
                List.of(),
                null,
                AllowCombination.REFLEXIVE_RELAY,
                (d, b) -> {},
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {}

                    @Override
                    public void onDisconnected() {}

                    @Override
                    public void onError(String error) {}

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                        offerSdpRef.set(sdp);
                        if (candidates != null) {
                            gatheredCandidates.addAll(candidates);
                        }
                        offerLatch.countDown();
                    }
                });

        session.createOffer();
        assertTrue(offerLatch.await(10, TimeUnit.SECONDS), "Offer creation timed out");

        assertNotNull(offerSdpRef.get());
        assertFalse(offerSdpRef.get().contains("typ host"), "Offer SDP must not contain host candidates");

        for (CandidatePacket packet : gatheredCandidates) {
            assertNotEquals(CandidateType.HOST_CANDIDATE, packet.type(), "No HOST candidate packet should be emitted");
        }
    }

    @Test
    @DisplayName("createOffer with ALL should allow gathering HOST candidates")
    void testOfferWithAllGathersCandidates() throws Exception {
        session = new WebRtcSession(factory);
        CountDownLatch offerLatch = new CountDownLatch(1);
        AtomicReference<String> offerSdpRef = new AtomicReference<>();
        List<CandidatePacket> gatheredCandidates = Collections.synchronizedList(new ArrayList<>());

        session.init(
                true, List.of(), null, AllowCombination.ALL, (d, b) -> {}, new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {}

                    @Override
                    public void onDisconnected() {}

                    @Override
                    public void onError(String error) {}

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onOfferCreated(String sdp, List<CandidatePacket> candidates) {
                        offerSdpRef.set(sdp);
                        if (candidates != null) {
                            gatheredCandidates.addAll(candidates);
                        }
                        offerLatch.countDown();
                    }
                });

        session.createOffer();
        assertTrue(offerLatch.await(10, TimeUnit.SECONDS), "Offer creation timed out");

        assertNotNull(offerSdpRef.get());
        // In local environment, ALL should gather at least one host candidate
        assertFalse(gatheredCandidates.isEmpty(), "Candidates should be gathered for ALL");
        assertTrue(
                gatheredCandidates.stream().anyMatch(p -> p.type() == CandidateType.HOST_CANDIDATE),
                "At least one HOST candidate should be gathered for ALL");
    }
}
