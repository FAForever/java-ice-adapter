package com.faforever.iceadapter.ice.peer.modules.fa;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PeerToFaModule implements ModuleBase, PeerEventListener {
    private static final String LOCALHOST = "127.0.0.1";
    private static final InetAddress LOCALHOST_ADDR;

    static {
        try {
            LOCALHOST_ADDR = InetAddress.getByName(LOCALHOST);
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (data[0] != FaToPeerModule.COMMAND_FA) {
            return;
        }
        int length = data.length;
        getSocketAndTrySend(data, 1, length - 1);
    }

    private void getSocketAndTrySend(byte[] data, int offset, int length) {
        DatagramSocket socket = peer.getFaSocket();
        if (socket == null) {
            log.error("Socket is null. Send to FA skipped");
            return;
        }
        send(socket, data, offset, length);
    }

    private void send(DatagramSocket socket, byte[] data, int offset, int length) {
        try {
            DatagramPacket packet = new DatagramPacket(data, offset, length, LOCALHOST_ADDR, peer.getLobbyPort());
            socket.send(packet);
        } catch (IOException e) {
            if (peer.isClosing()) {
                log.debug(
                        "Ignoring error while sending packet because the connection was closed {}",
                        peer.getPeerIdentifier());
            } else {
                log.error(
                        "Error while writing to local FA as peer (probably disconnecting from peer) {}",
                        peer.getPeerIdentifier(),
                        e);
                peer.lostConnect();
            }
        }
    }
}
