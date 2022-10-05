package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.PeerConnectivityCheckerModule;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.util.concurrent.RateLimiter;
import com.nbarraille.jjsonrpc.JJsonPeer;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "messageType")
interface OutgoingMessageV1 {
    UUID messageId();
}

record RegisterAsPeer(UUID messageId, String adapterVersion,
                      String userName) implements OutgoingMessageV1 {
}

record CoturnServer(String region, String host, int port, Double averageRTT) {
}

record UpdateCoturnList(UUID messageId, String connectedHost,
                        List<CoturnServer> knownServers) implements OutgoingMessageV1 {
}

record UpdateGameState(UUID messageId, GameState newState) implements OutgoingMessageV1 {
}

record UpdateGpgnetState(UUID messageId, String newState) implements OutgoingMessageV1 {
}

record ConnectToPeer(UUID messageId, int peerPlayerId, String peerName,
                     boolean localOffer) implements OutgoingMessageV1 {
}

record DisconnectFromPeer(UUID messageId, int peerPlayerId) implements OutgoingMessageV1 {
}

record UpdatePeerState(
        UUID messageId, int peerPlayerId, IceState iceState, CandidateType localCandidate,
        CandidateType remoteCandidate) implements OutgoingMessageV1 {
}

record UpdatePeerConnectivity(UUID messageId, int peerPlayerId, Float averageRTT,
                              Instant lastReceived) implements OutgoingMessageV1 {
}

@Slf4j
public class TelemetryDebugger implements Debugger {
    private final WebSocketClient websocketClient;
    private final ObjectMapper objectMapper;

    private final Map<Integer, RateLimiter> peerRateLimiter = new ConcurrentHashMap<>();

    public TelemetryDebugger(String telemetryServer, int gameId, int playerId) {
        Debug.register(this);

        URI uri = URI.create("%s/adapter/v1/game/%d/player/%d".formatted(telemetryServer, gameId, playerId));
        log.info("Open the telemetry ui via {}/app.html?gameId={}&playerId={}", telemetryServer.replaceFirst("ws", "http"), gameId, playerId);

        websocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                log.info("Telemetry websocket opened");
            }

            @Override
            public void onMessage(String message) {
                log.info("Telemetry websocket message: {}", message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.info("Telemetry websocket closed (reason: {})", reason);
            }

            @Override
            public void onError(Exception ex) {
                if (ex instanceof ConnectException) {
                    log.error("Error connecting to Telemetry websocket", ex);
                    Debug.remove(TelemetryDebugger.this);
                } else {
                    log.error("Error in Telemetry websocket", ex);

                }
            }
        };

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    private void sendMessage(OutgoingMessageV1 message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            websocketClient.send(json);
        } catch (IOException e) {
            log.error("Error on serialising message object: {}", message, e);
        }
    }

    @Override
    public void startupComplete() {
        try {
            if (!websocketClient.connectBlocking()) {
                return;
            }
        } catch (InterruptedException e) {
            Debug.remove(this);
            log.error("Failed to connect to telemetry websocket", e);
        }

        sendMessage(new RegisterAsPeer(
                UUID.randomUUID(),
                "SNAPSHOT",
                IceAdapter.login
        ));

        sendMessage(new UpdateCoturnList(
                UUID.randomUUID(),
                "faforever.com",
                List.of(new CoturnServer("Europe", "faforever.com", 3478, null))
        ));
    }

    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {
        log.info("RPC started");
        peerFuture.thenAccept(peer -> log.info("RPC connected"));
    }

    @Override
    public void gpgnetStarted() {
        sendMessage(new UpdateGpgnetState(
                UUID.randomUUID(),
                "WAITING_FOR_GAME"
        ));
    }

    @Override
    public void gpgnetConnectedDisconnected() {
        sendMessage(new UpdateGpgnetState(
                UUID.randomUUID(),
                GPGNetServer.isConnected() ? "GAME_CONNECTED" : "WAITING_FOR_GAME"
        ));
    }

    @Override
    public void gameStateChanged() {
        sendMessage(new UpdateGameState(
                UUID.randomUUID(), GPGNetServer.getGameState()
                .orElseThrow(() -> new IllegalStateException("gameState must not change to null")))
        );
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
        sendMessage(new UpdatePeerState(
                UUID.randomUUID(),
                peer.getRemoteId(),
                peer.getIce().getIceState(),
                Optional.ofNullable(peer.getIce().getComponent())
                        .map(Component::getSelectedPair)
                        .map(CandidatePair::getLocalCandidate)
                        .map(Candidate::getType)
                        .orElse(null),
                Optional.ofNullable(peer.getIce().getComponent())
                        .map(Component::getSelectedPair)
                        .map(CandidatePair::getRemoteCandidate)
                        .map(Candidate::getType)
                        .orElse(null)
        ));
    }

    @Override
    public void peerConnectivityUpdate(Peer peer) {
        if (!peerRateLimiter
                .computeIfAbsent(peer.getRemoteId(), i -> RateLimiter.create(1.0))
                .tryAcquire()) {
            // We only want to send one connectivity update per second (per peer)
            log.trace("Rate limiting prevents connectivity update for peer {} (id {})", peer.getRemoteLogin(), peer.getRemoteId());
            return;
        }

        log.trace("Sending connectivity update for peer {} (id {})", peer.getRemoteLogin(), peer.getRemoteId());

        sendMessage(new UpdatePeerConnectivity(
                UUID.randomUUID(),
                peer.getRemoteId(),
                Optional.ofNullable(peer.getIce().getConnectivityChecker())
                        .map(PeerConnectivityCheckerModule::getAverageRTT)
                        .orElse(null),
                Optional.ofNullable(peer.getIce().getConnectivityChecker())
                        .map(PeerConnectivityCheckerModule::getLastPacketReceived)
                        .map(Instant::ofEpochMilli)
                        .orElse(null)
        ));
    }
}
