package com.faforever.iceadapter.ice;

import com.google.common.primitives.Longs;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static com.faforever.iceadapter.debug.Debug.debug;

@Slf4j
/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
public class PeerConnectivityCheckerModule {

    private static final int ECHO_INTERVAL = 1000;

    private final PeerIceModule ice;
    private volatile boolean running = false;
    private volatile Thread checkerThread;

    @Getter private float averageRTT = 0.0f;
    @Getter private long lastPacketReceived;
    @Getter private long echosReceived = 0;
    @Getter private long invalidEchosReceived = 0;

    public PeerConnectivityCheckerModule(PeerIceModule ice) {
        this.ice = ice;
    }

    synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        log.debug("Starting connectivity checker for peer {}", ice.getPeer().getRemoteId());

        averageRTT = 0.0f;
        lastPacketReceived = System.currentTimeMillis();

        checkerThread = new Thread(this::checkerThread, getThreadName());
        checkerThread.setUncaughtExceptionHandler((t, e) -> log.error("Thread {} crashed unexpectedly", t.getName(), e));
        checkerThread.start();
    }

    private String getThreadName() {
        return "connectivityChecker-"+ice.getPeer().getRemoteId();
    }

    synchronized void stop() {
        if (!running) {
            return;
        }

        running = false;

        if (checkerThread != null) {
            checkerThread.interrupt();
            checkerThread = null;
        }
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

        int rtt = (int) (System.currentTimeMillis() - Longs.fromByteArray(Arrays.copyOfRange(data, offset + 1, length)));
        if (averageRTT == 0) {
            averageRTT = rtt;
        } else {
            averageRTT = (float) averageRTT * 0.8f + (float) rtt * 0.2f;
        }

        lastPacketReceived = System.currentTimeMillis();

        debug().peerConnectivityUpdate(ice.getPeer());
//      System.out.printf("Received echo from %d after %d ms, averageRTT: %d ms", ice.getPeer().getRemoteId(), rtt, (int) averageRTT);
    }

    private void checkerThread() {
        while (!Thread.currentThread().isInterrupted()) {
            log.trace("Running connectivity checker");

            byte[] data = new byte[9];
            data[0] = 'e';

            //Copy current time (long, 8 bytes) into array after leading prefix indicating echo
            System.arraycopy(Longs.toByteArray(System.currentTimeMillis()), 0, data, 1, 8);

            ice.sendViaIce(data, 0, data.length);

            debug().peerConnectivityUpdate(ice.getPeer());

            try {
                Thread.sleep(ECHO_INTERVAL);
            } catch (InterruptedException e) {
                log.warn("{} (sleeping checkerThread) was interrupted", Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return;
            }

            if (System.currentTimeMillis() - lastPacketReceived > 10000) {
                log.warn("Didn't receive any answer to echo requests for the past 10 seconds from {}, aborting connection", ice.getPeer().getRemoteLogin());
                new Thread(ice::onConnectionLost).start();
                return;
            }
        }

        log.info("{} stopped gracefully", Thread.currentThread().getName());
    }
}
