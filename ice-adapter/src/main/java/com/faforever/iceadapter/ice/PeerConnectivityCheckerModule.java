package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
public class PeerConnectivityCheckerModule {

    private static final int ECHO_INTERVAL = 1000;

    private final PeerIceModule ice;
    private final Lock lockIce = new ReentrantLock();
    private volatile boolean running = false;
    private volatile CompletableFuture<Void> checker;

    @Getter
    private float averageRTT = 0.0f;

    @Getter
    private long lastPacketReceived;

    @Getter
    private long echosReceived = 0;

    @Getter
    private long invalidEchosReceived = 0;

    public PeerConnectivityCheckerModule(PeerIceModule ice) {
        this.ice = ice;
    }

    void start() {
        LockUtil.executeWithLock(lockIce, () -> {
            if (running) {
                return;
            }

            running = true;
            log.debug("Starting connectivity checker for peer {}", ice.getPeer().getRemoteId());

            averageRTT = 0.0f;
            lastPacketReceived = System.currentTimeMillis();

            checker = CompletableFuture.runAsync(() -> {
                Thread.currentThread().setName(getThreadName());
                checkerThread();
            }, IceAdapter.getExecutor());
        });
    }

    private String getThreadName() {
        return "connectivityChecker-" + ice.getPeer().getRemoteId();
    }

    void stop() {
        LockUtil.executeWithLock(lockIce, () -> {
            if (!running) {
                return;
            }

            running = false;

            if (checker != null) {
                checker.cancel(true);
                checker = null;
            }
        });
    }

    /**
     * an echo has been received, RTT and last_received will be updated
     * @param data
     * @param offset
     * @param length
     */
    void echoReceived(byte[] data, int offset, int length) {
        echosReceived++;

        if (length != 9) {
            log.trace("Received echo of wrong length, length: {}", length);
            invalidEchosReceived++;
        }

        int rtt =
                (int) (System.currentTimeMillis() - Longs.fromByteArray(Arrays.copyOfRange(data, offset + 1, length)));
        if (averageRTT == 0) {
            averageRTT = rtt;
        } else {
            averageRTT = (float) averageRTT * 0.8f + (float) rtt * 0.2f;
        }

        lastPacketReceived = System.currentTimeMillis();

        debug().peerConnectivityUpdate(ice.getPeer());
        //      System.out.printf("Received echo from %d after %d ms, averageRTT: %d ms", ice.getPeer().getRemoteId(),
        // rtt, (int) averageRTT);
    }

    private void checkerThread() {
        while (!Thread.currentThread().isInterrupted() && running) {
            log.trace("Running connectivity checker");

            Peer peer = ice.getPeer();
            byte[] data = new byte[9];
            data[0] = 'e';

            // Copy current time (long, 8 bytes) into array after leading prefix indicating echo
            System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 1, 8);

            ice.sendViaIce(data, 0, data.length);

            debug().peerConnectivityUpdate(peer);

            try {
                Thread.sleep(ECHO_INTERVAL);
            } catch (InterruptedException e) {
                log.warn(
                        "{} (sleeping checkerThread) was interrupted",
                        Thread.currentThread().getName());
                return;
            }

            if (System.currentTimeMillis() - lastPacketReceived > 10000) {
                log.warn(
                        "Didn't receive any answer to echo requests for the past 10 seconds from {}, aborting connection",
                        peer.getRemoteLogin());
                CompletableFuture.runAsync(ice::onConnectionLost, IceAdapter.getExecutor());
                return;
            }
        }

        log.info("{} stopped gracefully", Thread.currentThread().getName());
    }
}
