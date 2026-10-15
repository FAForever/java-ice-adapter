package com.faforever.iceadapter.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebRtcResourceCleanupTest {

    @Test
    void testSessionCloseIsIdempotent() {
        WebRtcConnectionFactory factory = WebRtcConnectionFactory.getInstance();
        WebRtcSession session = new WebRtcSession(factory);

        session.init(true, List.of(), (data, isBinary) -> {}, new WebRtcSession.SessionStateHandler() {
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

        // Close once
        assertDoesNotThrow(session::close);

        // Close second time (must be idempotent, no exceptions)
        assertDoesNotThrow(session::close);

        // Sending data after close must return false without exceptions
        assertFalse(session.sendData(new byte[] {1, 2, 3}, true));
    }

    @Test
    void testFactoryShutdownAndReinitialization() {
        WebRtcConnectionFactory factory1 = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory1);
        assertNotNull(factory1.getFactory());

        // Shutdown factory
        assertDoesNotThrow(factory1::shutdown);

        // Second shutdown must be idempotent
        assertDoesNotThrow(factory1::shutdown);

        // Re-obtain factory instance (must create a new instance successfully)
        WebRtcConnectionFactory factory2 = WebRtcConnectionFactory.getInstance();
        assertNotNull(factory2);
        assertNotNull(factory2.getFactory());

        // Create and close session with re-obtained factory
        WebRtcSession session = new WebRtcSession(factory2);
        session.init(false, List.of(), (d, b) -> {}, new WebRtcSession.SessionStateHandler() {
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

        assertDoesNotThrow(session::close);
    }
}
