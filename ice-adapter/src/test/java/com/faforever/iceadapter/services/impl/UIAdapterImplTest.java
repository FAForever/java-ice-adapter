package com.faforever.iceadapter.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import java.util.Collections;
import java.util.Map;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

class UIAdapterImplTest {

    @Test
    void testGetWebRtcPeerInfoListPopulatesAndUpdates() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        GameSession gameSession = mock(GameSession.class);
        when(iceAdapter.getGameSession()).thenReturn(gameSession);

        Peer peer1 = mock(Peer.class);
        when(peer1.getRemoteId()).thenReturn(101);
        when(peer1.getRemoteLogin()).thenReturn("Alice");

        Peer peer2 = mock(Peer.class);
        when(peer2.getRemoteId()).thenReturn(102);
        when(peer2.getRemoteLogin()).thenReturn("Bob");

        when(gameSession.getPeers()).thenReturn(Map.of(101, peer1, 102, peer2));

        UIAdapterImpl uiAdapter = new UIAdapterImpl(iceAdapter);

        ObservableList<WebRtcPeerView> list = uiAdapter.getWebRtcPeerInfoList();

        assertEquals(2, list.size());
        assertEquals(101, list.get(0).getPeerId().get());
        assertEquals("Alice", list.get(0).getLogin().get());
        assertEquals(102, list.get(1).getPeerId().get());
        assertEquals("Bob", list.get(1).getLogin().get());

        // Peer 2 leaves
        when(gameSession.getPeers()).thenReturn(Map.of(101, peer1));
        list = uiAdapter.getWebRtcPeerInfoList();

        assertEquals(1, list.size());
        assertEquals(101, list.get(0).getPeerId().get());

        // All peers leave
        when(gameSession.getPeers()).thenReturn(Collections.emptyMap());
        list = uiAdapter.getWebRtcPeerInfoList();

        assertTrue(list.isEmpty());
    }
}
