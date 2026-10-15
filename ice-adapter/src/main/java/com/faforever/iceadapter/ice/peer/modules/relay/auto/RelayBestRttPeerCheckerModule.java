package com.faforever.iceadapter.ice.peer.modules.relay.auto;

import com.faforever.iceadapter.dto.command.relay.auto.info.RelayPingCommand;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.RelayPing;
import com.faforever.iceadapter.util.CollectionUtils;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically sends echo requests via the ICE data channel and initiates a reconnect after timeout
 * ONLY THE OFFERING ADAPTER of a connection will send echos and reoffer.
 */
@Slf4j
@RequiredArgsConstructor
public class RelayBestRttPeerCheckerModule implements ModuleBase, PeerEventListener {
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    private static final int ECHO_INTERVAL = 1000;
    private static final int BEST_RELAY_CALC = 1000;

    private final Peer peer;

    private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();

    @Override
    public void init() {
        peer.addEventListener(this);
        scheduledFutures.add(scheduledExecutorService.scheduleAtFixedRate(
                this::checkerThread, 0, ECHO_INTERVAL, TimeUnit.MILLISECONDS));
        scheduledFutures.add(scheduledExecutorService.scheduleAtFixedRate(
                this::calculateBestRelay, 0, BEST_RELAY_CALC, TimeUnit.MILLISECONDS));
    }

    private String getThreadName() {
        return "relayBestRttPeerCheckerModule-%s".formatted(peer.getPeerIdentifier());
    }

    @Override
    public void onClose(Peer peer, boolean hasClosed) {
        if (!hasClosed) {
            return;
        }

        for (ScheduledFuture<?> scheduledFuture : scheduledFutures) {
            if (!scheduledFuture.isCancelled()) {
                scheduledFuture.cancel(true);
            }
        }
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public void stop() {
        if (peer.isClosing()) {
            for (ScheduledFuture<?> scheduledFuture : scheduledFutures) {
                if (!scheduledFuture.isCancelled()) {
                    scheduledFuture.cancel(true);
                }
            }
            scheduledExecutorService.shutdownNow();
        }
    }

    private void checkerThread() {
        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return;
        }

        Thread.currentThread().setName(getThreadName());

        Map<Integer, Peer> allPeers = gameSession.getPeers();

        Map<Integer, RelayPing> rtts = peer.getRtts();

        Set<Integer> idsLast = rtts.keySet();
        Set<Integer> idsForSend = allPeers.entrySet().stream()
                .filter(entry -> {
                    Peer p = entry.getValue();
                    return !p.isClosing() && p.isConnected() && !Objects.equals(peer, p);
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (!Objects.equals(idsForSend, idsLast)) {
            Set<Integer> diff = new HashSet<>(idsLast);
            diff.removeAll(idsForSend);
            diff.forEach(rtts::remove);
        }

        for (Integer id : idsForSend) {
            Peer peerForSend = allPeers.get(id);
            if (peerForSend == null || !peerForSend.isAllowRelay()) {
                continue;
            }
            peerForSend.sendCommand(RelayPingCommand.builder()
                    .fromId(peer.getFromId())
                    .targetId(peer.getRemoteId())
                    .toTarget(true)
                    .echo(System.currentTimeMillis())
                    .build());
        }
        if (!CollectionUtils.isEmpty(idsForSend)) {
            log.info("Relay Ping send to {}", idsForSend);
        }
    }

    private void calculateBestRelay() {
        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return;
        }
        Thread.currentThread().setName(getThreadName());

        Map<Integer, RelayPing> rtts = peer.getRtts();

        List<Integer> ids = rtts.entrySet().stream()
                .filter(entry -> entry.getValue().isActual())
                .sorted(Comparator.comparing(entry -> entry.getValue().getRtt()))
                .map(Map.Entry::getKey)
                .toList();

        List<Integer> newBestRelays = new ArrayList<>();
        for (Integer id : ids) {
            Peer relay = gameSession.getPeer(id).orElse(null);

            if (relay == null || !relay.isConnected()) {
                continue;
            }
            newBestRelays.add(id);
            if (newBestRelays.size() >= 3) {
                break;
            }
        }

        List<Integer> bestRelays = peer.getBestRelays();
        if (bestRelays != null && Objects.equals(bestRelays, newBestRelays)) {
            return;
        }
        peer.setBestRelays(newBestRelays);
        log.info("New bestRelays {}", newBestRelays);
    }
}
