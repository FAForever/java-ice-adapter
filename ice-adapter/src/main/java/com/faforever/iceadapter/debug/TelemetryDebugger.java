package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.PeerConnectivityCheckerModule;
import com.faforever.iceadapter.telemetry.*;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.Component;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * {@link Debugger} implementation that streams ICE adapter lifecycle, GPGNet, and per-peer events
 * to the FAF telemetry server over a WebSocket.
 *
 * <p>The data flow is strictly outgoing: every payload type in
 * {@code com.faforever.iceadapter.telemetry} implements {@link OutgoingMessageV1}, and the only
 * inbound handler ({@link WebSocketClient#onMessage(String)}) just logs what arrives. As a result
 * telemetry is a debug-only observability channel and must never gate core ICE adapter behaviour.
 *
 * <p>Outgoing messages are decoupled from the calling thread by a
 * {@link LinkedBlockingDeque} drained by {@link #sendingLoop()} on a dedicated virtual thread.
 * The sending loop only starts once the socket is open, so messages produced while connecting
 * are buffered and sent after {@link RegisterAsPeer}. If the connect fails or times out,
 * telemetry is disabled for the rest of the session.
 */
@Slf4j
public class TelemetryDebugger implements Debugger, AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final WebSocketClient websocketClient;
    private final ObjectMapper objectMapper;

    private final Map<Integer, RateLimiter> peerRateLimiter = new ConcurrentHashMap<>();
    private final BlockingDeque<OutgoingMessageV1> messageQueue = new LinkedBlockingDeque<>();

    private volatile Thread connectThread;
    private volatile Thread sendingLoopThread;

    /**
     * Registers this debugger with {@link Debug}, builds the telemetry WebSocket client targeting
     * {@code <telemetryServer>/adapter/v1/game/<gameId>/player/<playerId>}. Neither the connection
     * nor the sending loop is started here; see {@link #startupComplete()}.
     *
     * @param telemetryServer base URI of the telemetry server (e.g. {@code wss://...}).
     * @param gameId          FAF game id this adapter instance belongs to.
     * @param playerId        FAF player id this adapter instance belongs to.
     */
    public TelemetryDebugger(String telemetryServer, int gameId, int playerId) {
        Debug.register(this);

        URI uri = URI.create("%s/adapter/v1/game/%d/player/%d".formatted(telemetryServer, gameId, playerId));
        log.info(
                "Open the telemetry ui via {}/app.html?gameId={}&playerId={}",
                telemetryServer.replaceFirst("ws", "http"),
                gameId,
                playerId);

        websocketClient = new WebSocketClient(uri) {
            /** Logs that the WebSocket handshake completed. The server handshake metadata is unused. */
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                log.info("Telemetry websocket opened");
            }

            /**
             * Logs an inbound telemetry message.
             *
             * <p>Telemetry is one-way (adapter -&gt; server) by design, so receiving a message is
             * unusual and worth surfacing in logs but never acted upon by the adapter.
             */
            @Override
            public void onMessage(String message) {
                log.info("Telemetry websocket message: {}", message);
            }

            /** Logs that the WebSocket has been closed, including the reason reported by the peer. */
            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.info("Telemetry websocket closed (reason: {})", reason);
            }

            /**
             * Handles errors raised by the WebSocket client.
             *
             * <p>A {@link ConnectException} indicates the telemetry server is unreachable; in that
             * case the surrounding {@link TelemetryDebugger} is removed so subsequent
             * {@code debug().*} calls iterate an empty debugger list and become no-ops. All other
             * exceptions are logged but the debugger is kept registered so transient errors don't
             * disable telemetry permanently.
             */
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

    /**
     * Enqueues an outgoing telemetry message for transmission by {@link #sendingLoop()}.
     *
     * <p>The queue is unbounded, so the put never waits for space, but it still throws
     * {@link InterruptedException} if the calling thread's interrupt flag is set. That exception
     * is rethrown wrapped as a {@link RuntimeException} so callers do not need to declare it.
     *
     * @param message message to enqueue.
     */
    private void sendMessage(OutgoingMessageV1 message) {
        try {
            messageQueue.put(message);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loop body of the dedicated {@code sendingLoop} virtual thread.
     *
     * <p>Blocks on {@link BlockingQueue#take()}, serializes each message to JSON via Jackson, and
     * sends it over the WebSocket. If the socket has dropped, {@link WebSocketClient#reconnectBlocking()}
     * is called before the message is sent so transient drops are transparent to producers.
     *
     * <p>Exits cleanly on interrupt; other exceptions are logged and the loop continues so a single
     * malformed message cannot stall the entire telemetry channel.
     */
    @SneakyThrows
    private void sendingLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            var message = messageQueue.take();
            try {
                String json = objectMapper.writeValueAsString(message);

                if (websocketClient.isClosed()) {
                    log.warn("Telemetry websocket is closed");
                    websocketClient.reconnectBlocking();
                    log.info("Telemetry websocket reconnected");
                }

                log.trace("Sending telemetry message: {}", json);
                websocketClient.send(json);
            } catch (InterruptedException e) {
                log.info("Sending loop interrupted");
                return;
            } catch (Exception e) {
                log.error("Error on sending message object: {}", message, e);
            }
        }
    }

    /**
     * Connects to the telemetry websocket server asynchronously, see {@link #connect()}.
     *
     * <p>Telemetry is a debug-only, strictly outgoing observability channel; no game logic depends
     * on it being connected. The connect is therefore performed on a virtual thread so that
     * {@code IceAdapter.start()} returns immediately and a slow or hung telemetry server can never
     * hold up startup.
     *
     * @see <a href="https://github.com/FAForever/java-ice-adapter/issues/42">Issue #42</a>
     */
    @Override
    public void startupComplete() {
        connectThread = Thread.ofVirtual().name("telemetry-connect").start(this::connect);
    }

    /**
     * Body of the {@code telemetry-connect} virtual thread.
     *
     * <p>Failure handling:
     * <ul>
     *   <li>If the connect fails or does not complete within {@link #CONNECT_TIMEOUT}, telemetry is
     *       disabled via {@link #disable()}. The timeout covers servers whose TCP layer is reachable
     *       but whose application layer never answers the WebSocket upgrade.</li>
     *   <li>If the connect thread is interrupted, telemetry is disabled and the interrupt flag is
     *       re-asserted on the current thread per Java best practice.</li>
     *   <li>On a successful connect, a {@link RegisterAsPeer} message is put at the head of the queue
     *       so it is sent before anything queued while connecting, then the sending loop is started.
     *       The reconnect logic in {@link #sendingLoop()} handles later transient drops.</li>
     * </ul>
     */
    private void connect() {
        try {
            if (!websocketClient.connectBlocking(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Could not connect to telemetry websocket within {}, disabling telemetry", CONNECT_TIMEOUT);
                disable();
                return;
            }
        } catch (InterruptedException e) {
            log.info("Telemetry websocket connect interrupted");
            disable();
            Thread.currentThread().interrupt();
            return;
        }

        // Messages queued while connecting are kept until now, RegisterAsPeer has to be sent before them
        messageQueue.addFirst(new RegisterAsPeer(
                UUID.randomUUID(), "java-ice-adapter/" + IceAdapter.getVersion(), IceAdapter.getLogin()));
        sendingLoopThread = Thread.ofVirtual().name("sendingLoop").start(this::sendingLoop);
    }

    /**
     * Removes this debugger via {@link Debug#remove(Debugger)} so subsequent {@code debug().*} calls
     * no longer reach it, drops everything queued so far and closes the websocket.
     */
    private void disable() {
        Debug.remove(this);
        messageQueue.clear();
        websocketClient.close();
    }

    /**
     * Logs that the JSON-RPC server has started and arranges to log again once a peer connects.
     *
     * <p>No telemetry message is sent here because RPC peer state is not part of the v1 telemetry
     * protocol; this hook exists purely for local diagnostics.
     *
     * @param peerFuture future that completes with the connected RPC peer.
     */
    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {
        log.info("RPC started");
        peerFuture.thenAccept(peer -> log.info("RPC connected"));
    }

    /**
     * Sends an {@link UpdateGpgnetState} indicating the GPGNet server is up and waiting for the game
     * client to connect.
     */
    @Override
    public void gpgnetStarted() {
        sendMessage(new UpdateGpgnetState(UUID.randomUUID(), "WAITING_FOR_GAME"));
    }

    /**
     * Sends an {@link UpdateGpgnetState} reflecting the current GPGNet connection state -
     * {@code GAME_CONNECTED} when {@link GPGNetServer#isConnected()} is {@code true}, otherwise
     * {@code WAITING_FOR_GAME}.
     */
    @Override
    public void gpgnetConnectedDisconnected() {
        sendMessage(new UpdateGpgnetState(
                UUID.randomUUID(), GPGNetServer.isConnected() ? "GAME_CONNECTED" : "WAITING_FOR_GAME"));
    }

    /**
     * Sends an {@link UpdateGameState} carrying the latest GPGNet game state.
     *
     * @throws IllegalStateException if {@link GPGNetServer#getGameState()} is empty - the contract
     *                               of this hook is that it is invoked on a state transition, so
     *                               an absent state would indicate a programming error upstream.
     */
    @Override
    public void gameStateChanged() {
        sendMessage(new UpdateGameState(
                UUID.randomUUID(),
                GPGNetServer.getGameState()
                        .orElseThrow(() -> new IllegalStateException("gameState must not change to null"))));
    }

    /**
     * Sends a {@link ConnectToPeer} event when the adapter starts attempting to reach a peer.
     *
     * @param id          remote player id.
     * @param login       remote player login.
     * @param localOffer  {@code true} if this adapter generated the SDP offer, {@code false} if it
     *                    is the answering side.
     */
    @Override
    public void connectToPeer(int id, String login, boolean localOffer) {
        sendMessage(new ConnectToPeer(UUID.randomUUID(), id, login, localOffer));
    }

    /**
     * Sends a {@link DisconnectFromPeer} event and drops any rate-limiter state held for the peer
     * so a future reconnect starts from a clean slate.
     *
     * @param id remote player id.
     */
    @Override
    public void disconnectFromPeer(int id) {
        peerRateLimiter.remove(id);
        sendMessage(new DisconnectFromPeer(UUID.randomUUID(), id));
    }

    /**
     * Sends an {@link UpdatePeerState} carrying the current ICE state and the candidate types of
     * the currently selected pair (local and remote), if any.
     *
     * <p>Unlike {@link #peerConnectivityUpdate(Peer)} this method is not rate-limited: ICE state
     * transitions are infrequent and each one is independently meaningful for diagnostics.
     *
     * @param peer peer whose ICE state changed.
     */
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
                        .orElse(null)));
    }

    /**
     * Sends an {@link UpdatePeerConnectivity} with the latest RTT and last-packet-received timestamp
     * for the given peer.
     *
     * <p>Rate-limited to one update per second per peer via a per-peer Guava {@link RateLimiter},
     * because connectivity samples can fire at the underlying STUN keepalive cadence and would
     * otherwise dominate the telemetry stream.
     *
     * @param peer peer whose connectivity sample is being reported.
     */
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
                Optional.ofNullable(peer.getIce().getConnectivityChecker())
                        .map(PeerConnectivityCheckerModule::getAverageRTT)
                        .orElse(null),
                Optional.ofNullable(peer.getIce().getConnectivityChecker())
                        .map(PeerConnectivityCheckerModule::getLastPacketReceived)
                        .map(Instant::ofEpochMilli)
                        .orElse(null)));
    }

    /**
     * Sends an {@link UpdateCoturnList} reporting the COTURN servers currently known to the adapter.
     * The first server's host is included as a convenience so the telemetry UI can display the
     * "primary" relay without pulling the whole list apart.
     *
     * @param servers current COTURN server list (may be empty).
     */
    @Override
    public void updateCoturnList(Collection<CoturnServer> servers) {
        sendMessage(new UpdateCoturnList(
                UUID.randomUUID(),
                servers.stream().map(CoturnServer::host).findFirst().orElse(null),
                servers));
    }

    /**
     * Interrupts the connect and sending loop threads, if started, so they can exit cleanly.
     *
     * <p>Implements {@link AutoCloseable} so callers can manage telemetry lifetime with
     * try-with-resources. The WebSocket itself is not closed here - the underlying
     * {@link WebSocketClient} owns its own close lifecycle and the JVM will tear it down on exit.
     */
    @Override
    public void close() {
        if (connectThread != null) {
            connectThread.interrupt();
        }
        if (sendingLoopThread != null) {
            sendingLoopThread.interrupt();
        }
    }
}
