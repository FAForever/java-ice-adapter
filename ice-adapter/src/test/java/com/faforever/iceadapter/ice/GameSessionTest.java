package com.faforever.iceadapter.ice;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.services.RpcConnection;
import com.faforever.iceadapter.webrtc.WebRtcConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameSessionTest {

    @AfterEach
    void tearDown() {
        // Cleanup singleton if initialized
        WebRtcConnectionFactory.getInstance().shutdown();
    }

    @Test
    void testCustomWebRtcConnectionFactoryIsPreserved() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();
        WebRtcConnectionFactory customFactory = mock(WebRtcConnectionFactory.class);

        GameSession session = new GameSession(options, customFactory, rpcConnection);

        assertSame(customFactory, session.getWebRtcConnectionFactory());
        session.close();
    }

    @Test
    void testDefaultWebRtcConnectionFactoryUsesSingleton() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();

        GameSession session = new GameSession(rpcConnection, options);

        assertSame(WebRtcConnectionFactory.getInstance(), session.getWebRtcConnectionFactory());
        session.close();
    }
}
