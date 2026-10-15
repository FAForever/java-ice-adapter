package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.DatagramSocketUtils;
import com.faforever.iceadapter.util.LockUtil;
import java.net.DatagramSocket;
import java.net.SocketException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FASocketModule implements ModuleBase {
    private static final String LOCK_MODULE = "FASocketModule";

    private final Peer peer;

    public void firstStart() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), () -> {
            try {
                peer.setFaSocket(initForwarding(peer.getPreferredPort(), peer.getLocalPort()));
            } catch (Exception e) {
                log.error("Could not connect to FA for {}", peer.getPeerIdentifier(), e);
            }
        });
    }

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::checkSocket);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopSocket);
    }

    @SneakyThrows(SocketException.class)
    private DatagramSocket initForwarding(int port, Integer localPort) {
        try {
            if (localPort != null) {
                port = localPort;
            }
            DatagramSocket socket = new DatagramSocket(port);
            DatagramSocketUtils.resizeBuffer(socket);
            log.debug("Now forwarding data to peer {} on port {}", peer.getPeerIdentifier(), peer.getLocalPort());
            return socket;
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", peer.getPeerIdentifier(), e);
            throw e;
        }
    }

    private void checkSocket() {
        if (peer.isClosing()) {
            return;
        }
        DatagramSocket socket = peer.getFaSocket();
        if (socket == null || socket.isClosed()) {
            log.error("Socket {} is null or closed", peer.getPeerIdentifier());
            try {
                log.warn("Trying to connect to FA for {}", peer.getPeerIdentifier());
                socket = initForwarding(peer.getPreferredPort(), peer.getLocalPort());
                peer.setFaSocket(socket);
            } catch (Exception e) {
                log.error("Could not connect to FA for {}", peer.getPeerIdentifier(), e);
            }
        }
    }

    private void stopSocket() {
        DatagramSocket socket = peer.getFaSocket();
        if (peer.isClosing() && socket != null && !socket.isClosed()) {
            socket.close();
            peer.setFaSocket(null);
        }
    }
}
