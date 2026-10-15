package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebRtcSession Configuration with AllowCombination")
class WebRtcAllowCombinationConfigTest {

    private WebRtcConnectionFactory factory;
    private WebRtcSession session;

    @BeforeEach
    void setUp() {
        factory = WebRtcConnectionFactory.getInstance();
        session = new WebRtcSession(factory);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    @DisplayName("Combination ALL configures RTCIceTransportPolicy.ALL and keeps all flags unblocked")
    void testConfigCombinationAll() {
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
                });

        RTCConfiguration config = session.getConfig();
        assertEquals(RTCIceTransportPolicy.ALL, config.iceTransportPolicy);
        PortAllocatorConfig pac = config.portAllocatorConfig;
        if (pac != null) {
            assertFalse(pac.isStunDisabled());
            assertFalse(pac.isRelayDisabled());
            assertFalse(pac.isDefaultLocalCandidateDisabled());
        }
    }

    @Test
    @DisplayName("Combination RELAY configures RTCIceTransportPolicy.RELAY and disables local candidates")
    void testConfigCombinationRelay() {
        session.init(
                true, List.of(), null, AllowCombination.RELAY, (d, b) -> {}, new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {}

                    @Override
                    public void onDisconnected() {}

                    @Override
                    public void onError(String error) {}

                    @Override
                    public void onRemoteDescriptionSet() {}
                });

        RTCConfiguration config = session.getConfig();
        assertEquals(RTCIceTransportPolicy.RELAY, config.iceTransportPolicy);
        PortAllocatorConfig pac = config.portAllocatorConfig;
        assertNotNull(pac);
        assertTrue(pac.isDefaultLocalCandidateDisabled());
        assertTrue(pac.isAdapterEnumerationDisabled());
    }

    @Test
    @DisplayName("Combination REFLEXIVE_RELAY disables adapter enumeration and default local candidate")
    void testConfigCombinationReflexiveRelay() {
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
                });

        RTCConfiguration config = session.getConfig();
        assertEquals(RTCIceTransportPolicy.ALL, config.iceTransportPolicy);
        PortAllocatorConfig pac = config.portAllocatorConfig;
        assertNotNull(pac);
        assertTrue(pac.isDefaultLocalCandidateDisabled());
        assertTrue(pac.isAdapterEnumerationDisabled());
        assertFalse(pac.isStunDisabled());
    }

    @Test
    @DisplayName("Combination HOST_RELAY disables STUN gathering")
    void testConfigCombinationHostRelay() {
        session.init(
                true,
                List.of(),
                null,
                AllowCombination.HOST_RELAY,
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
                });

        RTCConfiguration config = session.getConfig();
        assertEquals(RTCIceTransportPolicy.ALL, config.iceTransportPolicy);
        PortAllocatorConfig pac = config.portAllocatorConfig;
        assertNotNull(pac);
        assertTrue(pac.isStunDisabled());
        assertFalse(pac.isDefaultLocalCandidateDisabled());
    }
}
