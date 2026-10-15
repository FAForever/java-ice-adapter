package com.faforever.iceadapter.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.RelayPing;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PeerViewTest {

    @Test
    void testDirectAndRelayRttSeparated() {
        Peer peer = mock(Peer.class);
        when(peer.getCombination()).thenReturn(AllowCombination.ALL);
        when(peer.getAverageRtt()).thenReturn(Optional.of(45.4f));

        RelayPing ping = new RelayPing("Rhiza");
        ping.updateRtt(62.4f);
        when(peer.getRtts()).thenReturn(Map.of(2, ping));
        when(peer.getBestRelays()).thenReturn(List.of(2));

        when(peer.getCandidateTypes()).thenReturn(List.of(new Pair<>("srflx", "prflx")));

        PeerView view = new PeerView(1, "Player1");
        view.update(peer);

        assertEquals("45", view.getDirectRtt().get());
        assertEquals("62", view.getRelayRtt().get());
        assertEquals("Rhiza", view.getRelayLogin().get());
        assertEquals("srflx", view.getLocalCand().get());
        assertEquals("prflx", view.getRemoteCand().get());
    }

    @Test
    void testRttWhenNoRelayOrDirect() {
        Peer peer = mock(Peer.class);
        when(peer.getCombination()).thenReturn(AllowCombination.ALL);
        when(peer.getAverageRtt()).thenReturn(Optional.empty());
        when(peer.getRtts()).thenReturn(Map.of());
        when(peer.getBestRelays()).thenReturn(List.of());
        when(peer.getCandidateTypes()).thenReturn(List.of());

        PeerView view = new PeerView(1, "Player1");
        view.update(peer);

        assertEquals("–", view.getDirectRtt().get());
        assertEquals("–", view.getRelayRtt().get());
        assertEquals("", view.getRelayLogin().get());
        assertEquals("-", view.getLocalCand().get());
        assertEquals("-", view.getRemoteCand().get());
    }
}
