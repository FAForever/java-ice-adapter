package com.faforever.iceadapter.rpc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RPCHandlerTest {

    private static final int LOCAL_PLAYER_ID = 1;
    private static final int REMOTE_PLAYER_ID = 2;

    private RPCHandler rpcHandler;
    private FafRpcCallbacks callbacks;
    private GPGNetServer gpgNetServer;
    private GameSession gameSession;
    private Peer remotePeer;
    private IceAdapter previousAdapterInstance;

    @BeforeEach
    void setUp() {
        callbacks = mock(FafRpcCallbacks.class);
        gpgNetServer = mock(GPGNetServer.class);
        gameSession = mock(GameSession.class);
        remotePeer = mock(Peer.class);

        IceOptions options = mock(IceOptions.class);
        when(options.getId()).thenReturn(LOCAL_PLAYER_ID);

        IceAdapter adapter = new IceAdapter();
        adapter.setIceOptions(options);
        adapter.setGameSession(gameSession);

        previousAdapterInstance = IceAdapter.INSTANCE;
        IceAdapter.INSTANCE = adapter;

        when(gameSession.getPeers()).thenReturn(Map.of(REMOTE_PLAYER_ID, remotePeer));

        rpcHandler = new RPCHandler(7236, callbacks, gpgNetServer);
    }

    @AfterEach
    void tearDown() {
        IceAdapter.INSTANCE = previousAdapterInstance;
    }

    @Test
    void testWebRtcOfferSignalingRoute() {
        String json =
                """
                        {
                            "srcId": 2,
                            "destId": 1,
                            "ufrag": "offer",
                            "password": "v=0\\r\\no=- 123 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\n",
                            "candidates": []
                        }
                        """;

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        ArgumentCaptor<CandidatesMessage> captor = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(remotePeer, times(1)).iceMessageFromRPC(captor.capture());

        CandidatesMessage captured = captor.getValue();
        assertEquals("offer", captured.ufrag());
        assertTrue(captured.isOffer());
        assertFalse(captured.isAnswer());
        assertFalse(captured.isCandidate());
        assertEquals(2, captured.srcId());
        assertEquals(1, captured.destId());
        assertTrue(captured.password().contains("v=0"));
        assertTrue(captured.candidates().isEmpty());
    }

    @Test
    void testWebRtcAnswerSignalingRoute() {
        String json =
                """
                        {
                            "srcId": 2,
                            "destId": 1,
                            "ufrag": "answer",
                            "password": "v=0\\r\\no=- 456 2 IN IP4 127.0.0.1\\r\\n",
                            "candidates": []
                        }
                        """;

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        ArgumentCaptor<CandidatesMessage> captor = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(remotePeer, times(1)).iceMessageFromRPC(captor.capture());

        CandidatesMessage captured = captor.getValue();
        assertEquals("answer", captured.ufrag());
        assertTrue(captured.isAnswer());
        assertFalse(captured.isOffer());
        assertFalse(captured.isCandidate());
        assertEquals(2, captured.srcId());
        assertEquals(1, captured.destId());
        assertTrue(captured.password().contains("456"));
    }

    @Test
    void testWebRtcIceCandidateSignalingRoute() {
        String candidateStr = "candidate:1 1 UDP 2122260223 127.0.0.1 50000 typ host";
        String json =
                """
                        {
                            "srcId": 2,
                            "destId": 1,
                            "ufrag": "candidate",
                            "password": "%s",
                            "candidates": []
                        }
                        """
                        .formatted(candidateStr);

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        ArgumentCaptor<CandidatesMessage> captor = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(remotePeer, times(1)).iceMessageFromRPC(captor.capture());

        CandidatesMessage captured = captor.getValue();
        assertEquals("candidate", captured.ufrag());
        assertTrue(captured.isCandidate());
        assertFalse(captured.isOffer());
        assertFalse(captured.isAnswer());
        assertEquals(2, captured.srcId());
        assertEquals(1, captured.destId());
        assertEquals(candidateStr, captured.password());
    }

    @Test
    void testLegacyCandidatesMessageRoute() {
        String json =
                """
                        {
                            "srcId": 2,
                            "destId": 1,
                            "password": "secretPassword",
                            "ufrag": "userFrag",
                            "candidates": []
                        }
                        """;

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        ArgumentCaptor<CandidatesMessage> captor = ArgumentCaptor.forClass(CandidatesMessage.class);
        verify(remotePeer, times(1)).iceMessageFromRPC(captor.capture());

        CandidatesMessage captured = captor.getValue();
        assertEquals(2, captured.srcId());
        assertEquals(1, captured.destId());
        assertEquals("secretPassword", captured.password());
        assertEquals("userFrag", captured.ufrag());
        assertFalse(captured.isOffer());
        assertFalse(captured.isAnswer());
        assertFalse(captured.isCandidate());
        assertTrue(captured.candidates().isEmpty());
    }

    @Test
    void testMismatchedDestIdIgnored() {
        String json =
                """
                        {
                            "srcId": 2,
                            "destId": 999,
                            "ufrag": "offer",
                            "password": "v=0\\r\\n",
                            "candidates": []
                        }
                        """;

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        verify(remotePeer, never()).iceMessageFromRPC(any(CandidatesMessage.class));
    }

    @Test
    void testMismatchedSrcIdIgnored() {
        String json =
                """
                        {
                            "srcId": 3,
                            "destId": 1,
                            "ufrag": "offer",
                            "password": "v=0\\r\\n",
                            "candidates": []
                        }
                        """;

        rpcHandler.iceMsg(REMOTE_PLAYER_ID, json);

        verify(remotePeer, never()).iceMessageFromRPC(any(CandidatesMessage.class));
    }

    @Test
    void testInvalidJsonIgnored() {
        assertDoesNotThrow(() -> rpcHandler.iceMsg(REMOTE_PLAYER_ID, "invalid { json"));
        assertDoesNotThrow(() -> rpcHandler.iceMsg(REMOTE_PLAYER_ID, null));

        verify(remotePeer, never()).iceMessageFromRPC(any(CandidatesMessage.class));
    }
}
