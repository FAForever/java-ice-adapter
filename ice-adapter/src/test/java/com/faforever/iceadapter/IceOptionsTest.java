package com.faforever.iceadapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.base.TestGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.RpcConnection;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.services.impl.UIAdapterImpl;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class IceOptionsTest {

    @Test
    void testDefaultShowIpAddressesIsFalse() {
        IceOptions options = new IceOptions();
        assertFalse(options.isShowIpAddresses());
    }

    @Test
    void testShowIpAddressesCliOption() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--show-ip-addresses");
        assertTrue(options.isShowIpAddresses());
    }

    @Test
    void testShowPeerIpsCliAlias() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--show-peer-ips");
        assertTrue(options.isShowIpAddresses());
    }

    @Test
    void testShowIpCliAlias() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--show-ip");
        assertTrue(options.isShowIpAddresses());
    }

    @Test
    void testConstructorWithShowIpAddresses() {
        IceOptions options =
                new IceOptions(1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, true, true);
        assertTrue(options.isShowIpAddresses());

        IceOptions optionsHidden =
                new IceOptions(1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, false, true);
        assertFalse(optionsHidden.isShowIpAddresses());
    }

    @Test
    void testUIAdapterReflectsShowIpAddresses() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        IceOptions options = new IceOptions();
        options.setShowIpAddresses(false);
        when(iceAdapter.getIceOptions()).thenReturn(options);

        UIAdapter uiAdapter = new UIAdapterImpl(iceAdapter);
        assertFalse(uiAdapter.isShowIpAddresses());

        options.setShowIpAddresses(true);
        assertTrue(uiAdapter.isShowIpAddresses());
    }

    @Test
    void testWebRtcPeerViewHidesIpWhenDisabled() {
        Peer peer = mock(Peer.class);
        WebRtcSession session = mock(WebRtcSession.class);
        WebRtcSession.SessionStats stats = new WebRtcSession.SessionStats();
        stats.setLocalCandidateType("host");
        stats.setRemoteCandidateType("srflx");
        stats.setLocalAddress("192.168.1.10:50000");
        stats.setRemoteAddress("85.10.20.30:50001");

        when(peer.getWebRtcSession()).thenReturn(session);
        when(session.getStats()).thenReturn(stats);

        WebRtcPeerView view = new WebRtcPeerView(1, "Player1");

        // When showIpAddresses is false
        view.update(peer, false);
        assertEquals("host", view.getLocalCandidateType().get());
        assertEquals("srflx", view.getRemoteCandidateType().get());
        assertEquals("-", view.getLocalAddress().get());
        assertEquals("-", view.getRemoteAddress().get());

        // When showIpAddresses is true
        view.update(peer, true);
        assertEquals("host", view.getLocalCandidateType().get());
        assertEquals("srflx", view.getRemoteCandidateType().get());
        assertEquals("192.168.1.10:50000", view.getLocalAddress().get());
        assertEquals("85.10.20.30:50001", view.getRemoteAddress().get());
    }

    @Test
    void testDefaultShowAllowCombinationIsFalse() {
        IceOptions options = new IceOptions();
        assertFalse(options.isShowAllowCombination());
        assertFalse(options.isAllowCombination());
    }

    @Test
    void testShowAllowCombinationCliOption() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--show-allow-combination");
        assertTrue(options.isShowAllowCombination());
        assertTrue(options.isAllowCombination());
    }

    @Test
    void testAllowCombinationCliAlias() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--allow-combination");
        assertTrue(options.isShowAllowCombination());
        assertTrue(options.isAllowCombination());
    }

    @Test
    void testConstructorWithShowAllowCombination() {
        IceOptions options = new IceOptions(
                1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, false, true, true);
        assertTrue(options.isShowAllowCombination());

        IceOptions optionsHidden = new IceOptions(
                1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, false, true, false);
        assertFalse(optionsHidden.isShowAllowCombination());
    }

    @Test
    void testUIAdapterReflectsShowAllowCombination() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        IceOptions options = new IceOptions();
        options.setShowAllowCombination(false);
        when(iceAdapter.getIceOptions()).thenReturn(options);

        UIAdapter uiAdapter = new UIAdapterImpl(iceAdapter);
        assertFalse(uiAdapter.isShowAllowCombination());
        assertFalse(uiAdapter.isAllowCombination());

        options.setShowAllowCombination(true);
        assertTrue(uiAdapter.isShowAllowCombination());
        assertTrue(uiAdapter.isAllowCombination());
    }

    @Test
    void testUIAdapterSetCombination() {
        IceAdapter iceAdapter = mock(IceAdapter.class);
        GameSession gameSession = mock(GameSession.class);
        Peer peer = mock(Peer.class);
        PeerView peerView = new PeerView(42, "Player2");

        when(iceAdapter.getGameSession()).thenReturn(gameSession);
        when(gameSession.getPeer(42)).thenReturn(Optional.of(peer));

        UIAdapter uiAdapter = new UIAdapterImpl(iceAdapter);
        uiAdapter.setCombination(peerView, AllowCombination.HOST_RELAY);

        verify(peer).setCombination(AllowCombination.HOST_RELAY, true);
    }

    @Test
    void testDefaultAdditionalPacketForwardingIsTrue() {
        IceOptions options = new IceOptions();
        assertFalse(options.isAdditionalPacketForwarding());
    }

    @Test
    void testAdditionalPacketForwardingCliOption() {
        IceOptions options = new IceOptions();
        new CommandLine(options)
                .parseArgs("--id=1", "--game-id=2", "--login=Test", "--additional-packet-forwarding=false");
        assertFalse(options.isAdditionalPacketForwarding());

        options = new IceOptions();
        new CommandLine(options)
                .parseArgs("--id=1", "--game-id=2", "--login=Test", "--additional-packet-forwarding=true");
        assertTrue(options.isAdditionalPacketForwarding());
    }

    @Test
    void testAdditionalForwardingCliAlias() {
        IceOptions options = new IceOptions();
        new CommandLine(options).parseArgs("--id=1", "--game-id=2", "--login=Test", "--additional-forwarding=false");
        assertFalse(options.isAdditionalPacketForwarding());
    }

    @Test
    void testConstructorWithAdditionalPacketForwarding() {
        IceOptions options = new IceOptions(
                1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, false, true, true, false);
        assertFalse(options.isAdditionalPacketForwarding());

        IceOptions optionsEnabled = new IceOptions(
                1, 2, "Test", 7236, 0, 0, false, false, false, 0, 1, 250.0, null, true, false, true, true, true);
        assertTrue(optionsEnabled.isAdditionalPacketForwarding());
    }

    @Test
    void testPeerInheritsAdditionalPacketForwardingFromOptions() {
        RpcConnection rpcConnection = mock(RpcConnection.class);
        IceOptions options = new IceOptions();
        options.setId(1);
        options.setAdditionalPacketForwarding(true);

        TestGameSession session = new TestGameSession(rpcConnection, options, Set.of(PeerModule.values()));
        session.connectToPeer("RemoteUser", 2, true, 0, AllowCombination.ALL);

        Peer peer = session.getPeer(2).orElseThrow();
        assertTrue(peer.isAdditionalPacketForwarding());

        options.setAdditionalPacketForwarding(false);
        session.connectToPeer("RemoteUser2", 3, true, 0, AllowCombination.ALL);

        Peer peer2 = session.getPeer(3).orElseThrow();
        assertFalse(peer2.isAdditionalPacketForwarding());
    }
}
