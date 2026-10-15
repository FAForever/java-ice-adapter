package com.faforever.iceadapter.ice.peer.modules.fa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faforever.iceadapter.ice.peer.Peer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("FaToPeerModule Unit Tests")
class FaToPeerModuleTest {

    private Peer peer;
    private FaToPeerModule module;

    @BeforeEach
    void setUp() {
        peer = mock(Peer.class);
        module = new FaToPeerModule(peer);
    }

    @Test
    @DisplayName("Should block on socket.receive and forward packet when connected")
    void testReceiveAndForwardWhenConnected() throws IOException {
        when(peer.isConnected()).thenReturn(true);

        DatagramSocket socket = mock(DatagramSocket.class);
        byte[] payload = new byte[] {1, 2, 3, 4};

        doAnswer(invocation -> {
                    DatagramPacket p = invocation.getArgument(0);
                    System.arraycopy(payload, 0, p.getData(), 0, payload.length);
                    p.setLength(payload.length);
                    return null;
                })
                .when(socket)
                .receive(any(DatagramPacket.class));

        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        module.receive(socket, packet, buffer);

        verify(socket).receive(packet);

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(peer).sendToPeer(captor.capture());

        byte[] sent = captor.getValue();
        byte[] expected = new byte[] {FaToPeerModule.COMMAND_FA, 1, 2, 3, 4};
        assertArrayEquals(expected, sent);
    }

    @Test
    @DisplayName("Should still block on socket.receive but discard packet when disconnected and no relays exist")
    void testReceiveBlocksAndDiscardsWhenDisconnected() throws IOException {
        when(peer.isConnected()).thenReturn(false);
        when(peer.existBestRelays()).thenReturn(false);
        when(peer.isAdditionalPacketForwarding()).thenReturn(false);

        DatagramSocket socket = mock(DatagramSocket.class);
        byte[] payload = new byte[] {5, 6, 7};

        doAnswer(invocation -> {
                    DatagramPacket p = invocation.getArgument(0);
                    System.arraycopy(payload, 0, p.getData(), 0, payload.length);
                    p.setLength(payload.length);
                    return null;
                })
                .when(socket)
                .receive(any(DatagramPacket.class));

        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        module.receive(socket, packet, buffer);

        // Crucial: socket.receive must have been called to prevent busy-waiting loop
        verify(socket).receive(packet);
        // But data should not be forwarded because connection is down
        verify(peer, never()).sendToPeer(any());
    }

    @Test
    @DisplayName("isNeedReceive logic verification")
    void testIsNeedReceive() {
        when(peer.isConnected()).thenReturn(true);
        assertTrue(module.isNeedReceive());

        when(peer.isConnected()).thenReturn(false);
        when(peer.existBestRelays()).thenReturn(false);
        assertFalse(module.isNeedReceive());

        when(peer.existBestRelays()).thenReturn(true);
        assertTrue(module.isNeedReceive());
    }
}
