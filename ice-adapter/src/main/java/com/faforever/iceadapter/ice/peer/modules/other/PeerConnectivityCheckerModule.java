package com.faforever.iceadapter.ice.peer.modules.other;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityCheckerModule implements ModuleBase, PeerEventListener {

    public static final char COMMAND_ECHO = 'e';
    private static final String LOCK_CHECKER_MODULE = "PeerConnectivityCheckerModule";

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    private static final int ECHO_INTERVAL = 1000;
    private static final int TIMEOUT_BEFORE_LOST_CONNECT = 10000;
    private final Peer peer;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        if (!peer.isLocalOffer()) {
            return;
        }
        peer.setLastPacketReceived(System.currentTimeMillis());
        LockUtil.executeWithLock(peer.getLock(LOCK_CHECKER_MODULE), () -> {
            if (peer.isClosing()) {
                return;
            }
            if (isRunning()) {
                return;
            }

            log.debug("Starting connectivity checker for peer");
            scheduledFuture = scheduledExecutorService.scheduleAtFixedRate(
                    this::checkerThread, 0, ECHO_INTERVAL, TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        if (connecting) {
            start();
        } else {
            stop();
        }
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (data[0] != COMMAND_ECHO) {
            return;
        }
        int length = data.length;
        if (!peer.isLocalOffer()) {
            peer.sendToPeer(data);
        }
        if (length == 9) {
            peer.setLastEcho(Longs.fromByteArray(Arrays.copyOfRange(data, 1, length)));
            peer.getEchosReceived().incrementAndGet();
        } else {
            peer.getInvalidPacket().incrementAndGet();
            log.error("Invalid Echo received. length={}", length);
        }
    }

    @Override
    public Boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isDone();
    }

    private String getThreadName() {
        return "connectivityChecker-%s".formatted(peer.getPeerIdentifier());
    }

    @Override
    public void onClose(Peer peer, boolean hasClosed) {
        if (!hasClosed) {
            return;
        }
        stop();
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public void stop() {
        LockUtil.executeWithLock(peer.getLock(LOCK_CHECKER_MODULE), () -> {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
                scheduledFuture = null;
            }
            if (peer.isClosing()) {
                scheduledExecutorService.shutdownNow();
            }
        });
    }

    private void checkerThread() {
        if (!peer.isConnected()) {
            return;
        }
        Thread.currentThread().setName(getThreadName());
        log.trace("Running connectivity checker");

        byte[] data = new byte[9];
        data[0] = COMMAND_ECHO;

        // Copy current time (long, 8 bytes) into array after leading prefix indicating echo
        System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 1, 8);

        peer.sendToPeer(data);

        long lastPacketReceived = peer.getLastPacketReceived();
        long sinceLastReal = System.currentTimeMillis() - lastPacketReceived;

        if (sinceLastReal > TIMEOUT_BEFORE_LOST_CONNECT) {
            log.warn(
                    "{} No traffic (echo or game) for {} ms (> {} ms timeout). Closing connection.",
                    peer.getPeerIdentifier(),
                    lastPacketReceived,
                    TIMEOUT_BEFORE_LOST_CONNECT);
            peer.lostConnect();
            return;
        }
        debug().peerConnectivityUpdate(peer);
    }
}
