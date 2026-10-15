package com.faforever.iceadapter.gpgnet;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.NetworkToolbox;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class GPGNetServer implements AutoCloseable {
    private static final Object INSTANCE_LOCK = new Object();
    private static GPGNetServer INSTANCE;

    private final Lock lockSocket = new ReentrantLock();

    @Getter
    private final int gpgNetPort;

    @Getter
    private final int lobbyPort;

    private IceAdapter iceAdapter;
    private RPCService rpcService;
    private ServerSocket serverSocket;

    // single reference to current client (atomic for safe reads)
    private final AtomicReference<GPGNetClient> currentClient = new AtomicReference<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Setter
    private volatile LobbyInitMode lobbyInitMode = LobbyInitMode.NORMAL;

    public GPGNetServer(int gpgNetPort, int lobbyPort) {
        if (gpgNetPort == 0) {
            this.gpgNetPort = NetworkToolbox.findFreeTCPPort(20000, 65536);
            log.info("Generated GPGNET_PORT: {}", this.gpgNetPort);
        } else {
            this.gpgNetPort = gpgNetPort;
            log.info("Using GPGNET_PORT: {}", this.gpgNetPort);
        }

        if (lobbyPort == 0) {
            this.lobbyPort = NetworkToolbox.findFreeUDPPort(20000, 65536);
            log.info("Generated LOBBY_PORT: {}", this.lobbyPort);
        } else {
            this.lobbyPort = lobbyPort;
            log.info("Using LOBBY_PORT: {}", this.lobbyPort);
        }
    }

    public static LobbyInitMode getLobbyInitMode() {
        return INSTANCE != null ? INSTANCE.lobbyInitMode : LobbyInitMode.NORMAL;
    }

    public static int getStaticGpgNetPort() {
        return INSTANCE != null ? INSTANCE.gpgNetPort : 0;
    }

    public static int getStaticLobbyPort() {
        return INSTANCE != null ? INSTANCE.lobbyPort : 0;
    }

    public void sendToGpgNet(String header, Object... args) {
        // fast-path: if we have a client, send directly. Avoid piling up futures.
        GPGNetClient client = currentClient.get();
        if (client != null && client.isReadyForLobby()) {
            client.sendGpgnetMessage(header, args);
            return;
        }
        log.debug("Dropping GPGNet message because no client ready: {} {}", header, formatArgs(args));
    }

    public void init(IceAdapter iceAdapter, RPCService rpcService) {
        synchronized (INSTANCE_LOCK) {
            INSTANCE = this;
        }
        this.iceAdapter = iceAdapter;
        this.rpcService = rpcService;

        try {
            serverSocket = new ServerSocket(gpgNetPort);
        } catch (IOException e) {
            log.error("Couldn't start GPGNetServer", e);
            IceAdapter.close(-1);
            return;
        }

        // start accept loop on executor
        new Thread(this::acceptLoop).start();
        log.info("GPGNetServer started on port {}", this.gpgNetPort);
    }

    /**
     * Represents a connected FA client instance
     */
    @Getter
    public class GPGNetClient implements AutoCloseable {
        private volatile GameState gameState = GameState.NONE;

        private final Socket socket;
        private volatile boolean stopping = false;
        private FaDataOutputStream gpgnetOut;
        private final Lock lockStream = new ReentrantLock();
        private final CompletableFuture<GPGNetClient> lobbyFuture = new CompletableFuture<>();

        private GPGNetClient(Socket socket) throws IOException {
            this.socket = socket;
            this.gpgnetOut = new FaDataOutputStream(socket.getOutputStream());

            // notify RPC layer
            rpcService.onConnectionStateChanged("Connected");
            log.info("GPGNetClient connected from {}", socket.getRemoteSocketAddress());

            new Thread(this::listenerLoop).start();
        }

        private boolean isReadyForLobby() {
            return lobbyFuture.isDone();
        }

        /**
         * Process an incoming message from FA
         */
        private void processGpgnetMessage(String command, List<Object> args) {
            switch (command) {
                case "GameState" -> {
                    String stateName = (String) args.get(0);
                    gameState = GameState.getByName(stateName);
                    log.debug("GameState changed: {}", gameState.getName());

                    if (gameState == GameState.IDLE) {
                        sendGpgnetMessage(
                                "CreateLobby",
                                lobbyInitMode.getId(),
                                lobbyPort,
                                IceAdapter.getLogin(),
                                IceAdapter.getId(),
                                1);
                    } else if (gameState == GameState.LOBBY) {
                        lobbyFuture.complete(this);
                    }

                    debug().gameStateChanged();
                }
                case "GameEnded" -> {
                    GameSession gs = IceAdapter.getGameSessionSafe();
                    if (gs != null) {
                        gs.setGameEnded(true);
                        log.info("GameEnded received, stopping reconnects...");
                    }
                }
                default -> {
                    // forwarding to RPC
                }
            }

            log.info("Received GPGNet message: {} {}", command, formatArgs(args.toArray()));
            rpcService.onGpgNetMessageReceived(command, args);
        }

        public void sendGpgnetMessage(String command, Object... args) {
            if (stopping) return;
            LockUtil.executeWithLock(lockStream, () -> {
                try {
                    if (gpgnetOut != null) {
                        gpgnetOut.writeMessage(command, args);
                        log.info("Sent GPGNet message: {} {}", command, formatArgs(args));
                    }
                } catch (IOException e) {
                    log.error("Error while communicating with FA (output), assuming shutdown", e);
                    // schedule connection lost handling outside of lock to avoid potential deadlocks
                    executor.submit(GPGNetServer.this::onGpgnetConnectionLost);
                }
            });
        }

        private void listenerLoop() {
            log.debug("Listening for GPG messages from {}", socket.getRemoteSocketAddress());
            try (InputStream in = socket.getInputStream();
                    var gpgnetIn = new FaDataInputStream(in)) {
                while (!stopping) {
                    String command = gpgnetIn.readString();
                    List<Object> args = gpgnetIn.readChunks();

                    // If this client is no longer the current active one, stop listening
                    GPGNetClient active = currentClient.get();
                    if (active != this) {
                        log.info(
                                "Listener noticing it's no longer active client, stopping listener: {}",
                                socket.getRemoteSocketAddress());
                        break;
                    }

                    processGpgnetMessage(command, args);
                }
            } catch (SocketException se) {
                log.warn("SocketException in listener, assuming FA shutdown: {}", se.toString());
                executor.submit(GPGNetServer.this::onGpgnetConnectionLost);
            } catch (IOException e) {
                log.error("Error while communicating with FA (input), assuming shutdown", e);
                executor.submit(GPGNetServer.this::onGpgnetConnectionLost);
            }
            log.debug("GPGNet listener exiting for {}", socket.getRemoteSocketAddress());
        }

        @Override
        public void close() {
            stopping = true;
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("Error closing client socket", e);
            }
        }
    }

    /**
     * Called when the connection to FA is lost or a new connection is established while already connected.
     * This method removes and closes the current client and triggers ICE shutdown outside of the client lock.
     */
    private void onGpgnetConnectionLost() {
        log.info("GPGNet connection lost");

        // remove and close the client under lock
        LockUtil.executeWithLock(lockSocket, () -> {
            GPGNetClient prevClient = currentClient.getAndSet(null);
            if (prevClient != null) {
                prevClient.close();
                // create a fresh lobby future if necessary is handled per-client
                rpcService.onConnectionStateChanged("Disconnected");
            }
        });

        // perform the potentially blocking and cross-module shutdown outside of the lock to avoid deadlocks
        if (currentClient.get() != null) {
            iceAdapter.onFAShutdown();
            debug().gpgnetConnectedDisconnected();
        }
    }

    private void acceptLoop() {
        log.info("Accept loop started for GPGNetServer");
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();

                // handle new connection serially under lock
                LockUtil.executeWithLock(lockSocket, () -> {
                    GPGNetClient existing = currentClient.get();
                    if (existing != null) {
                        // close existing client first (synchronously)
                        existing.close();
                        currentClient.set(null);
                    }

                    try {
                        GPGNetClient client = new GPGNetClient(socket);
                        currentClient.set(client);
                        // when lobby is ready, the client will complete its lobbyFuture
                        debug().gpgnetConnectedDisconnected();
                    } catch (IOException e) {
                        log.error("Failed to create GPGNetClient", e);
                        try {
                            socket.close();
                        } catch (IOException ex) {
                            log.warn("Failed to close socket after failed client creation", ex);
                        }
                    }
                });

            } catch (SocketException se) {
                log.info("Server socket closed or interrupted: {}", se.toString());
                break;
            } catch (IOException e) {
                log.error("Could not listen on socket", e);
            }
        }

        log.info("Accept loop terminating");
    }

    public boolean isConnected() {
        return currentClient.get() != null;
    }

    public boolean isServerRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    public static Optional<GameState> getGameState() {
        return Optional.ofNullable(INSTANCE).map(s -> s.currentClient.get()).map(GPGNetClient::getGameState);
    }

    /**
     * Stops the GPGNetServer and thereby the connection to a currently connected client
     */
    @Override
    public void close() {
        log.info("Stopping GPGNetServer");

        executor.shutdown();

        // stop accept loop by closing server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Could not close gpgnet server socket", e);
        }

        // close current client
        GPGNetClient client = currentClient.getAndSet(null);
        if (client != null) {
            client.close();
        }

        log.info("GPGNetServer stopped");
    }

    // utility: format args to string
    private static String formatArgs(Object... args) {
        return Stream.of(args)
                .map(arg -> arg instanceof Double d ? d.intValue() + "" : String.valueOf(arg))
                .collect(Collectors.joining(" "));
    }
}
