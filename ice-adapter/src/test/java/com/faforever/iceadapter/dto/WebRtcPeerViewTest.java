package com.faforever.iceadapter.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import org.junit.jupiter.api.Test;

class WebRtcPeerViewTest {

    @Test
    void testUpdateWithWebRtcSessionAndIpAddresses() {
        Peer peer = mock(Peer.class);
        when(peer.getEchoRtt()).thenReturn(28.5f);

        WebRtcSession session = mock(WebRtcSession.class);
        WebRtcSession.SessionStats stats = new WebRtcSession.SessionStats();
        stats.setPeerConnectionState("CONNECTED");
        stats.setIceConnectionState("CONNECTED");
        stats.setDtlsState("CONNECTED");
        stats.setDataChannelState("OPEN");
        stats.setDataChannelLabel("fa-data");
        stats.setCandidatePairState("succeeded");
        stats.setNominated(true);
        stats.setLocalCandidateType("host");
        stats.setRemoteCandidateType("srflx");
        stats.setLocalAddress("192.168.1.10:50000");
        stats.setRemoteAddress("85.120.40.2:50001");
        stats.setRttMs(42.3f);
        stats.setAvailableOutgoingBitrate(150_000.0);
        stats.setAvailableIncomingBitrate(250_000.0);
        stats.setMessagesSent(100);
        stats.setMessagesReceived(150);
        stats.setBytesSent(2048);
        stats.setBytesReceived(5120);
        stats.setPacketsSent(120);
        stats.setPacketsReceived(160);
        stats.setPacketsDiscardedOnSend(2);

        when(session.getStats()).thenReturn(stats);
        when(peer.getWebRtcSession()).thenReturn(session);

        WebRtcPeerView view = new WebRtcPeerView(42, "PlayerOne");
        view.update(peer, true);

        assertEquals(42, view.getPeerId().get());
        assertEquals("PlayerOne", view.getLogin().get());
        assertEquals("CONNECTED", view.getPeerConnectionState().get());
        assertEquals("CONNECTED", view.getIceConnectionState().get());
        assertEquals("CONNECTED", view.getDtlsState().get());
        assertEquals("OPEN", view.getDataChannelState().get());
        assertEquals("fa-data", view.getDataChannelLabel().get());
        assertEquals("succeeded", view.getSelectedPairState().get());
        assertEquals("Yes", view.getNominated().get());
        assertEquals("host", view.getLocalCandidateType().get());
        assertEquals("srflx", view.getRemoteCandidateType().get());
        assertEquals("192.168.1.10:50000", view.getLocalAddress().get());
        assertEquals("85.120.40.2:50001", view.getRemoteAddress().get());
        assertEquals("42.3", view.getRttMs().get());
        assertEquals("28.5", view.getEchoRttMs().get());
        assertEquals("150.0 kbps", view.getAvailableOutgoingBitrate().get());
        assertEquals("250.0 kbps", view.getAvailableIncomingBitrate().get());
        assertEquals("100", view.getMessagesSent().get());
        assertEquals("150", view.getMessagesReceived().get());
        assertEquals("2.00 KB", view.getBytesSent().get());
        assertEquals("5.00 KB", view.getBytesReceived().get());
        assertEquals("120", view.getPacketsSent().get());
        assertEquals("160", view.getPacketsReceived().get());
        assertEquals("2", view.getPacketsDiscarded().get());
    }

    @Test
    void testUpdateHidingIpAddresses() {
        Peer peer = mock(Peer.class);
        when(peer.getEchoRtt()).thenReturn(15.0f);

        WebRtcSession session = mock(WebRtcSession.class);
        WebRtcSession.SessionStats stats = new WebRtcSession.SessionStats();
        stats.setLocalAddress("192.168.1.10:50000");
        stats.setRemoteAddress("85.120.40.2:50001");
        stats.setLocalCandidateType("host");
        stats.setRemoteCandidateType("srflx");

        when(session.getStats()).thenReturn(stats);
        when(peer.getWebRtcSession()).thenReturn(session);

        WebRtcPeerView view = new WebRtcPeerView(7, "SecretAgent");
        view.update(peer, false);

        assertEquals("-", view.getLocalAddress().get());
        assertEquals("-", view.getRemoteAddress().get());
        assertEquals("host", view.getLocalCandidateType().get());
        assertEquals("srflx", view.getRemoteCandidateType().get());
    }

    @Test
    void testUpdateWhenNoWebRtcSession() {
        Peer peer = mock(Peer.class);
        when(peer.getEchoRtt()).thenReturn(35.0f);
        when(peer.getWebRtcSession()).thenReturn(null);

        WebRtcPeerView view = new WebRtcPeerView(99, "OfflineGuy");
        view.update(peer, true);

        assertEquals("No WebRTC", view.getPeerConnectionState().get());
        assertEquals("-", view.getIceConnectionState().get());
        assertEquals("-", view.getDtlsState().get());
        assertEquals("-", view.getDataChannelState().get());
        assertEquals("-", view.getRttMs().get());
        assertEquals("35.0", view.getEchoRttMs().get());
    }
}
