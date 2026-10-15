package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.CandidateType;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.telemetry.*;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.util.concurrent.RateLimiter;
import com.nbarraille.jjsonrpc.JJsonPeer;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;

@Slf4j
@EqualsAndHashCode
public class TelemetryDebugger implements Debugger, AutoCloseable {
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final Duration RECONNECT_BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(30);

    private final GPGNetServer gpgNetServer;
    private final URI websocketUri;
    private volatile WebSocketClient websocketClient;
    private final ObjectMapper objectMapper;

    private final Map<Integer, RateLimiter> peerRateLimiter = new ConcurrentHashMap<>();
    private final BlockingQueue<OutgoingMessageV1> messageQueue = new LinkedBlockingQueue<>(100);

    private final Thread sendingLoopThread;

    private volatile boolean shouldRun = false;
    private volatile boolean disabled = false;
    private int reconnectAttempt = 0;

    public TelemetryDebugger(GPGNetServer gpgNetServer, String telemetryServer, int gameId, int playerId) {
        this.gpgNetServer = gpgNetServer;
        websocketUri = URI.create("%s/adapter/v1/game/%d/player/%d".formatted(telemetryServer, gameId, playerId));
        log.info(
                "Open the telemetry ui via {}/app.html?gameId={}&playerId={}",
                telemetryServer.replaceFirst("ws", "http"),
                gameId,
                playerId);

        createNewWebSocketClient();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sendingLoopThread = new Thread(this::sendingLoop, "telemetry-sending-loop");
        sendingLoopThread.start();
    }

    private void createNewWebSocketClient() {
        this.websocketClient = new WebSocketClient(websocketUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                log.trace("Telemetry websocket opened");
            }

            @Override
            public void onMessage(String message) {
                log.debug("Telemetry websocket message: {}", message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.debug("Telemetry websocket closed (code: {}, reason: {})", code, reason);
            }

            @Override
            public void onError(Exception ex) {
                if (ex instanceof ConnectException) {
                    log.warn("Failed to connect to telemetry server", ex);
                } else {
                    log.error("Telemetry websocket error", ex);
                }
            }
        };
    }

    private void sendMessage(OutgoingMessageV1 message) {
        if (!disabled && !messageQueue.offer(message)) {
            log.trace("Telemetry message queue is full. Dropping message: {}", message.getType());
        }
    }

    @SneakyThrows
    private void sendingLoop() {
        try {
            while (shouldRun) {
                OutgoingMessageV1 message = messageQueue.take();

                if (!ensureConnected()) {
                    log.warn("Failed to send telemetry message (no connection): {}", message.getType());
                    continue;
                }

                try {
                    String json = objectMapper.writeValueAsString(message);
                    websocketClient.send(json);
                    log.trace("Sent telemetry message: {}", json);
                } catch (WebsocketNotConnectedException e) {
                    log.warn("Telemetry websocket not connected: {}", message.getType());
                } catch (Exception e) {
                    log.error("Failed to serialize or send telemetry message: {}", message, e);
                }
            }
        } catch (InterruptedException e) {
            // Restore interrupt and exit cleanly
            Thread.currentThread().interrupt();
            log.debug("Telemetry sending loop interrupted, shutting down.");
        } catch (Exception e) {
            log.error("Unexpected error in telemetry sending loop", e);
        } finally {
            close();
            log.debug("Telemetry sending loop terminated.");
        }
    }

    private boolean ensureConnected() throws InterruptedException {
        while (shouldRun && !websocketClient.isOpen()) {
            if (!shouldRun) {
                return false;
            }

            // Exponential latency with jitter
            Duration delay = RECONNECT_BASE_DELAY.multipliedBy((long) Math.pow(2, Math.min(reconnectAttempt, 5)));
            delay = delay.plusMillis(ThreadLocalRandom.current().nextLong(0, 1000));
            delay = Duration.ofMillis(Math.min(delay.toMillis(), MAX_RECONNECT_DELAY.toMillis()));

            log.debug(
                    "Attempting to connect to telemetry server... Attempt {}/{}",
                    reconnectAttempt + 1,
                    MAX_RECONNECT_ATTEMPTS);

            Thread.sleep(delay.toMillis());

            try {
                createNewWebSocketClient();
                if (websocketClient.connectBlocking()) {
                    reconnectAttempt = 0;
                    return true;
                } else {
                    log.warn(
                            "Failed to connect to telemetry websocket (attempt {}/{})",
                            reconnectAttempt + 1,
                            MAX_RECONNECT_ATTEMPTS);
                }
            } catch (Exception e) {
                log.warn("Exception during connect attempt {}/{}", reconnectAttempt + 1, MAX_RECONNECT_ATTEMPTS, e);
            }

            reconnectAttempt++;

            if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                log.error("Max reconnect attempts reached. Stopping telemetry debugger.");
                shouldRun = false;
                Debug.remove(this);
                return false;
            }
        }
        return true;
    }

    @Override
    public void startupComplete() {
        sendMessage(new RegisterAsPeer(
                UUID.randomUUID(), "java-ice-adapter/" + IceAdapter.getVersion(), IceAdapter.getLogin()));
    }

    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {
        log.info("RPC started");
        peerFuture.thenAccept(peer -> log.info("RPC connected"));
    }

    @Override
    public void gpgnetStarted() {
        sendMessage(new UpdateGpgnetState(UUID.randomUUID(), "WAITING_FOR_GAME"));
    }

    @Override
    public void gpgnetConnectedDisconnected() {
        sendMessage(new UpdateGpgnetState(
                UUID.randomUUID(), gpgNetServer.isConnected() ? "GAME_CONNECTED" : "WAITING_FOR_GAME"));
    }

    @Override
    public void gameStateChanged() {
        sendMessage(new UpdateGameState(
                UUID.randomUUID(),
                GPGNetServer.getGameState()
                        .orElseThrow(() -> new IllegalStateException("gameState must not change to null"))));
    }

    @Override
    public void connectToPeer(int id, String login, boolean localOffer) {
        sendMessage(new ConnectToPeer(UUID.randomUUID(), id, login, localOffer));
    }

    @Override
    public void disconnectFromPeer(int id) {
        peerRateLimiter.remove(id);
        sendMessage(new DisconnectFromPeer(UUID.randomUUID(), id));
    }

    @Override
    public void peerStateChanged(Peer peer) {
        CandidateType localType = null;
        CandidateType remoteType = null;
        if (peer.getWebRtcSession() != null) {
            WebRtcSession.SessionStats s = peer.getWebRtcSession().getStats();
            localType = parseWebRtcCandidateType(s.getLocalCandidateType());
            remoteType = parseWebRtcCandidateType(s.getRemoteCandidateType());
        }

        sendMessage(
                new UpdatePeerState(UUID.randomUUID(), peer.getRemoteId(), peer.getIceState(), localType, remoteType));
    }

    private static CandidateType parseWebRtcCandidateType(String typeStr) {
        if (typeStr == null) return null;
        return switch (typeStr.toLowerCase()) {
            case "host" -> CandidateType.HOST_CANDIDATE;
            case "srflx" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
            case "prflx" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
            case "relay" -> CandidateType.RELAYED_CANDIDATE;
            default -> null;
        };
    }

    @Override
    public void peerConnectivityUpdate(Peer peer) {
        if (!peerRateLimiter
                .computeIfAbsent(peer.getRemoteId(), i -> RateLimiter.create(1.0))
                .tryAcquire()) {
            // We only want to send one connectivity update per second (per peer)
            log.trace(
                    "Rate limiting prevents connectivity update for peer {} (id {})",
                    peer.getRemoteLogin(),
                    peer.getRemoteId());
            return;
        }

        log.trace("Sending connectivity update for peer {} (id {})", peer.getRemoteLogin(), peer.getRemoteId());

        sendMessage(new UpdatePeerConnectivity(
                UUID.randomUUID(),
                peer.getRemoteId(),
                peer.getRtt(),
                peer.getLastReceived().map(Instant::ofEpochMilli).orElse(null)));
    }

    @Override
    public void updateCoturnList(Collection<CoturnServer> servers) {
        sendMessage(new UpdateCoturnList(
                UUID.randomUUID(),
                servers.stream().map(CoturnServer::host).findFirst().orElse(null),
                servers));
    }

    @Override
    public void close() {
        shouldRun = false;
        if (websocketClient != null) {
            websocketClient.close();
        }
        sendingLoopThread.interrupt();
        try {
            sendingLoopThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
