package com.faforever.iceadapter.ice;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.util.LockUtil;
import com.google.common.primitives.Longs;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityCheckerModule {

    private static final int ECHO_INTERVAL = 1000;

    private final PeerIceModule ice;
    private final Lock lockIce = new ReentrantLock();
    private volatile boolean running = false;
    private volatile Thread checkerThread;

    @Getter
    private float averageRTT = 0.0f;

    @Getter
    private long lastPacketReceived;

    @Getter
    private long echosReceived = 0;

    @Getter
    private long invalidEchosReceived = 0;

    void start() {
        LockUtil.executeWithLock(lockIce, () -> {
            if (running) {
                return;
            }

            running = true;
            log.debug("Starting connectivity checker for peer {}", ice.getPeer().getRemoteId());

            averageRTT = 0.0f;
            lastPacketReceived = System.currentTimeMillis();

            checkerThread = Thread.ofVirtual()
                    .name(getThreadName())
                    .uncaughtExceptionHandler((t, e) -> log.error("Thread {} crashed unexpectedly", t.getName(), e))
                    .start(this::checkerThread);
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

            if (checkerThread != null) {
                checkerThread.interrupt();
                checkerThread = null;
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
        if (length == 10 && data[offset + 1] != localOfferToInt()) {
            ice.sendViaIce(data, 0, length);
            return;
        }
        echosReceived++;

        if (length != 10) {
            log.trace("Received echo of wrong length, length: {}", length);
            invalidEchosReceived++;
        }

        long timeFromEchoMillis = Longs.fromByteArray(Arrays.copyOfRange(data, offset + 2, length));
        long currentTimeMillis = System.currentTimeMillis();

        long rtt = currentTimeMillis - timeFromEchoMillis;

        averageRTT = averageRTT == 0 ? rtt : averageRTT * 0.8f + (float) rtt * 0.2f;

        lastPacketReceived = currentTimeMillis;

        debug().peerConnectivityUpdate(ice.getPeer());
    }

    private void checkerThread() {
        while (!Thread.currentThread().isInterrupted() && running) {
            log.trace("Running connectivity checker");

            Peer peer = ice.getPeer();
            sendEcho();

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

    private void sendEcho() {
        byte[] data = new byte[10];
        data[0] = 'e';
        data[1] = (byte) localOfferToInt();
        // Copy current time (long, 8 bytes) into array after leading prefix indicating echo
        System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 2, 8);

        ice.sendViaIce(data, 0, data.length);
    }

    private int localOfferToInt() {
        Peer peer = ice.getPeer();
        return peer.isLocalOffer() ? 1 : 0;
    }
}
