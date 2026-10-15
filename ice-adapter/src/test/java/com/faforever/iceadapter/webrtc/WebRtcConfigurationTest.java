package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.IceServer;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebRtcConfigurationTest {

    @Test
    void testIceServerToWebRtcServerStun() {
        IceServer stunServer = new IceServer(
                IceServer.TypeServer.STUN,
                new IceServer.ServerAddress("stun.l.google.com", 19302, IceServer.TransportProtocol.UDP));

        RTCIceServer rtcServer = stunServer.toWebRtcServer();
        assertNotNull(rtcServer);
        assertTrue(rtcServer.urls.contains("stun:stun.l.google.com:19302"));
        assertNull(rtcServer.username);
        assertNull(rtcServer.password);
    }

    @Test
    void testIceServerToWebRtcServerTurnUdp() {
        IceServer turnServer = new IceServer(
                IceServer.TypeServer.TURN,
                new IceServer.ServerAddress("turn.faforever.com", 3478, IceServer.TransportProtocol.UDP));
        turnServer.setTurnUsername("testUser");
        turnServer.setTurnCredential("testPass");

        RTCIceServer rtcServer = turnServer.toWebRtcServer();
        assertNotNull(rtcServer);
        assertTrue(rtcServer.urls.contains("turn:turn.faforever.com:3478"));
        assertEquals("testUser", rtcServer.username);
        assertEquals("testPass", rtcServer.password);
    }

    @Test
    void testIceServerToWebRtcServerTurnTcp() {
        IceServer turnServer = new IceServer(
                IceServer.TypeServer.TURN,
                new IceServer.ServerAddress("turn.faforever.com", 3478, IceServer.TransportProtocol.TCP));
        turnServer.setTurnUsername("testUser");
        turnServer.setTurnCredential("testPass");

        RTCIceServer rtcServer = turnServer.toWebRtcServer();
        assertNotNull(rtcServer);
        assertTrue(rtcServer.urls.contains("turn:turn.faforever.com:3478?transport=tcp"));
    }

    @Test
    void testWebRtcSessionConfigurationWithPortsAndRelay() {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        WebRtcSession session = new WebRtcSession(factory);

        IceOptions options = new IceOptions();
        options.setForceRelay(true);
        options.setMinPort(50000);
        options.setMaxPort(50100);

        IceServer enabledStun = new IceServer(
                IceServer.TypeServer.STUN,
                new IceServer.ServerAddress("stun.cloudflare.com", 3478, IceServer.TransportProtocol.UDP));
        IceServer disabledStun = new IceServer(
                IceServer.TypeServer.STUN,
                new IceServer.ServerAddress("stun.disabled.com", 3478, IceServer.TransportProtocol.UDP));
        disabledStun.setEnabled(false);

        session.init(
                true,
                List.of(enabledStun, disabledStun),
                options,
                (data, isBinary) -> {},
                new WebRtcSession.SessionStateHandler() {
                    @Override
                    public void onConnected() {}

                    @Override
                    public void onDisconnected() {}

                    @Override
                    public void onError(String error) {}

                    @Override
                    public void onOfferCreated(String sdp) {}

                    @Override
                    public void onAnswerCreated(String sdp) {}

                    @Override
                    public void onRemoteDescriptionSet() {}

                    @Override
                    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {}
                });

        assertEquals(RTCIceTransportPolicy.RELAY, session.getConfig().iceTransportPolicy);
        assertNotNull(session.getConfig().portAllocatorConfig);
        assertEquals(50000, session.getConfig().portAllocatorConfig.minPort);
        assertEquals(50100, session.getConfig().portAllocatorConfig.maxPort);

        assertEquals(1, session.getConfig().iceServers.size());
        assertTrue(session.getConfig().iceServers.get(0).urls.contains("stun:stun.cloudflare.com:3478"));

        session.close();
    }
}
