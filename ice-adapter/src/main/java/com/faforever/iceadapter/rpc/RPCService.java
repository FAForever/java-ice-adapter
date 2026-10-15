package com.faforever.iceadapter.rpc;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ui.InfoWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nbarraille.jjsonrpc.JJsonPeer;
import com.nbarraille.jjsonrpc.TcpServer;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles communication between client and adapter, opens a server for the client to connect to
 */
@Slf4j
@RequiredArgsConstructor
@Data
public class RPCService implements AutoCloseable {

    private static final String NOT_CONNECTION = "N/A";
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static TcpServer tcpServer;
    private static volatile boolean skipRPCMessages = false;

    private final int port;
    private String host;

    public void init(GPGNetServer gpgNetServer, FafRpcCallbacks callbacks) {
        host = NOT_CONNECTION;
        log.info("Creating RPC server on port {}", port);

        RPCHandler rpcHandler = new RPCHandler(port, callbacks, gpgNetServer);
        tcpServer = new TcpServer(port, rpcHandler);
        tcpServer.start();

        debug().rpcStarted(tcpServer.getFirstPeer());
        tcpServer.getFirstPeer().thenAccept(firstPeer -> {
            host = String.valueOf(firstPeer.getSocket().getInetAddress());
            firstPeer.onConnectionLost(() -> {
                host = NOT_CONNECTION;
                GameState gameState = gpgNetServer.getGameState().orElse(null);
                if (gameState == GameState.LAUNCHING) {
                    skipRPCMessages = true;
                    log.warn("Lost connection to first RPC Peer. GameState: LAUNCHING, NOT STOPPING!");
                    if (InfoWindow.INSTANCE == null) {
                        Debug.ENABLE_INFO_WINDOW = true;
                        Debug.init();
                    }
                    InfoWindow.INSTANCE.showWindow();
                } else {
                    log.info("Lost connection to first RPC Peer. GameState: {}, Stopping adapter...", gameState);
                    callbacks.close();
                }
            });
        });
    }

    public void onConnectionStateChanged(String newState) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onConnectionStateChanged", List.of(newState));
        }
    }

    public void onGpgNetMessageReceived(String header, List<Object> chunks) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onGpgNetMessageReceived", List.of(header, chunks));
        }
    }

    public void onIceMsg(CandidatesMessage candidatesMessage) {
        if (!skipRPCMessages) {
            try {
                getPeerOrWait()
                        .sendNotification(
                                "onIceMsg",
                                List.of(
                                        candidatesMessage.srcId(),
                                        candidatesMessage.destId(),
                                        objectMapper.writeValueAsString(candidatesMessage)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void onIceConnectionStateChanged(long localPlayerId, long remotePlayerId, String state) {
        if (!skipRPCMessages) {
            getPeerOrWait()
                    .sendNotification("onIceConnectionStateChanged", List.of(localPlayerId, remotePlayerId, state));
        }
    }

    public void onConnected(long localPlayerId, long remotePlayerId, boolean connected) {
        if (!skipRPCMessages) {
            getPeerOrWait().sendNotification("onConnected", List.of(localPlayerId, remotePlayerId, connected));
        }
    }

    /**
     * Blocks until a peer is connected (the client)
     *
     * @return the currently connected peer (the client)
     */
    public JJsonPeer getPeerOrWait() {
        try {
            return tcpServer.getFirstPeer().get();
        } catch (Exception e) {
            log.error("Error on fetching first peer", e);
        }
        return null;
    }

    @Override
    public void close() {
        tcpServer.stop();
    }
}
