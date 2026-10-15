package com.faforever.iceadapter.ice.peer.modules.fa;

import static com.faforever.iceadapter.util.DatagramSocketUtils.MAX_SIZE_PACKET;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.LockUtil;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FaToPeerModule implements ModuleBase {
    public static final char COMMAND_FA = 'd';
    private static final String LOCK_MODULE = "FAListenerModule";
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    private final Peer peer;
    private volatile Future<?> listener;

    @Override
    public void start() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::startListeners);
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_MODULE), this::stopListeners);
    }

    private void startListeners() {
        if (listener == null || listener.isDone()) {
            listener = executor.submit(this::faListener);
        }
    }

    private void stopListeners() {
        if (peer.isClosing()) {
            if (listener != null && !listener.isDone()) {
                listener.cancel(true);
                listener = null;
            }
            executor.shutdownNow();
        }
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {
        byte[] buffer = new byte[MAX_SIZE_PACKET];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!peer.isClosing()) {
            DatagramSocket socket = peer.getFaSocket();
            if (socket != null) {
                receiveCatch(socket, packet, buffer);
            } else {
                log.error("Socket is null. Receive from FA skipped");
                break;
            }
        }
        log.debug("No longer listening for messages from FA for peer");
    }

    private void receiveCatch(DatagramSocket socket, DatagramPacket packet, byte[] buffer) {
        try {
            receive(socket, packet, buffer);
        } catch (SocketException se) {
            // socket closed or network error
            if (peer.isClosing()) {
                log.debug("FA listener shutting down for peer: {}", se.toString());
            } else {
                log.warn("SocketException in FA listener for peer: {}", se.toString());
                // Try to trigger ICE reconnect safely
                try {
                    peer.lostConnect();
                } catch (Exception ex) {
                    log.debug("Error while requesting ICE reconnect after socket exception", ex);
                }
            }
        } catch (IOException e) {
            if (peer.isClosing()) {
                log.debug("Ignoring error while receiving packet because the connection was closed as peer");
            } else {
                log.debug("Error while reading from local FA as peer (probably disconnecting from peer)", e);
                try {
                    peer.lostConnect();
                } catch (Exception ex) {
                    log.debug("Error while requesting ICE reconnect after IO error", ex);
                }
            }
        }
    }

    boolean isNeedReceive() {
        return peer.isConnected()
                || (peer.isAdditionalPacketForwarding() && peer.existBestRelays())
                || peer.existBestRelays();
    }

    void receive(DatagramSocket socket, DatagramPacket packet, byte[] buffer) throws IOException {
        packet.setData(buffer, 0, buffer.length);
        socket.receive(packet);
        if (!isNeedReceive()) {
            return;
        }
        int length = packet.getLength();
        if (length == 0) {
            return;
        }

        onFaDataReceived(buffer, packet.getOffset(), length);
    }

    void onFaDataReceived(byte[] buffer, int offset, int length) {
        byte[] data = new byte[length + 1];
        data[0] = COMMAND_FA;
        System.arraycopy(buffer, offset, data, 1, length);
        peer.sendToPeer(data);
    }

    /**
     * Data received from FA, prepends prefix and sends it via ICE to the other peer
     *
     * @param faData
     */
    void onFaDataReceived(byte[] faData) {
        onFaDataReceived(faData, 0, faData.length);
    }
}
