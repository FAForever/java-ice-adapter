package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates a scheduler that can terminate the connection when the time expires.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerConnectivityTimeoutModule implements AutoCloseable {
    private final PeerIceModule ice;
    private final IceOptions iceOptions;
    private final ScheduledExecutorService scheduledExecutor;
    private final GPGNetServer gpgNetServer;

    private ScheduledFuture<?> scheduledClosed;

    public void start() {
        if (scheduledClosed == null) {
            gpgNetServer.getGameState().ifPresent(state -> {
                if (state == GameState.LAUNCHING) {
                    log.info("Start timeout when {}, timeout seconds: {}", state, iceOptions.getTimeoutSecondsInGame());
                    scheduledClosed = scheduledExecutor.schedule(this::closeConnectionByTimeout, iceOptions.getTimeoutSecondsInGame(), TimeUnit.SECONDS);
                }
            });
        }
    }

    public void stopIfExist() {
        if (scheduledClosed != null) {
            scheduledClosed.cancel(true);
            scheduledClosed = null;
        }
    }

    private void closeConnectionByTimeout() {
        Peer peer = ice.getPeer();
        log.info("Close {} by timeout", peer.getPeerIdentifier());
        peer.close();
    }

    @Override
    public void close() {
        stopIfExist();
    }
}
