package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceServerChecker {
    private static final int INTERVAL = 3;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final IceOptions options;
    private final IceGameSession gameSession;

    private ScheduledFuture<?> scheduledFuture;

    public void start() {
        if (scheduledFuture != null) {
            return;
        }
        scheduledFuture = scheduler.scheduleAtFixedRate(this::checkerThread, 0, INTERVAL, TimeUnit.MINUTES);
    }

    private void checkerThread() {
        gameSession.getIceServers().stream()
                .filter(IceServer::isTurn)
                .filter(IceServer::isAuto)
                .forEach(server -> {
                    server.setEnabled(server.hasAcceptableLatency(options.getAcceptableLatency()));
                });
    }

    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
        scheduler.shutdown();
    }
}
