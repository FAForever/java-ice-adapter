package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.Peer;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbarraille.jjsonrpc.JJsonPeer;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "messageType")
@JsonSubTypes(@JsonSubTypes.Type(value = RegisterAsPeer.class, name = "registerAsPeer"))
interface OutgoingMessageV1 {
    UUID messageId();
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "messageType")
@JsonSubTypes(@JsonSubTypes.Type(value = RegisterAsPeer.class, name = "registerAsPeer"))
interface IncomingMessageV1 {
    UUID messageId();
}

record RegisterAsPeer(UUID messageId, String adapterVersion, int gameId, int playerId,
                      String userName) implements OutgoingMessageV1 {
}

@Slf4j
public class TelemetryDebugger implements Debugger {
    private final WebSocketClient websocketClient;
    private final ObjectMapper objectMapper;

    public TelemetryDebugger(int gameId) {
        websocketClient = new WebSocketClient(URI.create("ws://localhost:8080/ws/v1/game/" + gameId)) {
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
                log.error("Error in Telemetry websocket", ex);
            }
        };

        objectMapper = new ObjectMapper();
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
            websocketClient.connectBlocking();
            sendMessage(new RegisterAsPeer(
                    UUID.randomUUID(),
                    "SNAPSHOT",
                    IceAdapter.gameId,
                    IceAdapter.id,
                    IceAdapter.login
            ));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {

    }

    @Override
    public void gpgnetStarted() {

    }

    @Override
    public void gpgnetConnectedDisconnected() {

    }

    @Override
    public void gameStateChanged() {

    }

    @Override
    public void connectToPeer(int id, String login, boolean localOffer) {

    }

    @Override
    public void disconnectFromPeer(int id) {

    }

    @Override
    public void peerStateChanged(Peer peer) {

    }

    @Override
    public void peerConnectivityUpdate(Peer peer) {

    }
}
