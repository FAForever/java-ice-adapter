package com.faforever.iceadapter.gpgnet;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.NetworkToolbox;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GPGNetServer implements AutoCloseable {
    private static GPGNetServer INSTANCE;

    private final Lock lockSocket = new ReentrantLock();

    private int gpgnetPort;
    private int lobbyPort;
    private RPCService rpcService;
    private ServerSocket serverSocket;
    private volatile GPGNetClient currentClient;

    // Used by other services to get a callback on FA connecting
    private volatile CompletableFuture<GPGNetClient> clientFuture = new CompletableFuture<>();

    public void sendToGpgNet(String header, Object... args) {
        clientFuture.thenAccept(gpgNetClient ->
                gpgNetClient.getLobbyFuture().thenRun(() -> gpgNetClient.sendGpgnetMessage(header, args)));
    }

    @Setter
    private volatile LobbyInitMode lobbyInitMode = LobbyInitMode.NORMAL;

    public static LobbyInitMode getLobbyInitMode() {
        return INSTANCE.lobbyInitMode;
    }

    public void init(int gpgnetPort, int lobbyPort, RPCService rpcService) {
        INSTANCE = this;
        this.rpcService = rpcService;

        if (gpgnetPort == 0) {
            this.gpgnetPort = NetworkToolbox.findFreeTCPPort(20000, 65536);
            log.info("Generated GPGNET_PORT: {}", this.gpgnetPort);
        } else {
            this.gpgnetPort = gpgnetPort;
            log.info("Using GPGNET_PORT: {}", this.gpgnetPort);
        }

        if (lobbyPort == 0) {
            this.lobbyPort = NetworkToolbox.findFreeUDPPort(20000, 65536);
            log.info("Generated LOBBY_PORT: {}", this.lobbyPort);
        } else {
            this.lobbyPort = lobbyPort;
            log.info("Using LOBBY_PORT: {}", this.lobbyPort);
        }

        try {
            this.serverSocket = new ServerSocket(this.gpgnetPort);
        } catch (IOException e) {
            log.error("Couldn't start GPGNetServer", e);
            IceAdapter.close(-1);
        }

        CompletableFuture.runAsync(this::acceptThread, IceAdapter.getExecutor());
        log.info("GPGNetServer started");
    }

    /**
     * Represents a client (a game instance) connected to this GPGNetServer
     */
    @Getter
    public class GPGNetClient {
        private volatile GameState gameState = GameState.NONE;

        private final Socket socket;
        private final Thread listenerThread;
        private volatile boolean stopping = false;
        private FaDataOutputStream gpgnetOut;
        private final Lock lockStream = new ReentrantLock();
        private final CompletableFuture<GPGNetClient> lobbyFuture = new CompletableFuture<>();

        private GPGNetClient(Socket socket) {
            this.socket = socket;

            try {
                gpgnetOut = new FaDataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                log.error("Could not create GPGNet output steam to FA", e);
            }
            listenerThread = Thread.startVirtualThread(this::listenerThread);

            rpcService.onConnectionStateChanged("Connected");
            log.info("GPGNetClient has connected");
        }

        /**
         * Process an incoming message from FA
         */
        private void processGpgnetMessage(String command, List<Object> args) {
            switch (command) {
                case "GameState" -> {
                    gameState = GameState.getByName((String) args.get(0));
                    log.debug("New GameState: {}", gameState.getName());

                    if (gameState == GameState.IDLE) {
                        sendGpgnetMessage(
                                "CreateLobby",
                                lobbyInitMode.getId(),
                                GPGNetServer.getLobbyPort(),
                                IceAdapter.getLogin(),
                                IceAdapter.getId(),
                                1);
                    } else if (gameState == GameState.LOBBY) {
                        lobbyFuture.complete(this);
                    }

                    debug().gameStateChanged();
                }
                case "GameEnded" -> {
                    if (IceAdapter.getGameSession() != null) {
                        IceAdapter.getGameSession().setGameEnded(true);
                        log.info("GameEnded received, stopping reconnects...");
                    }
                }
                default -> {
                    // No need to log, as we are not processing all messages but just forward them via RPC
                }
            }

            log.info(
                    "Received GPGNet message: {} {}",
                    command,
                    args.stream().map(Object::toString).collect(Collectors.joining(" ")));
            rpcService.onGpgNetMessageReceived(command, args);
        }

        /**
         * Send a message to this FA instance via GPGNet
         */
        public void sendGpgnetMessage(String command, Object... args) {
            LockUtil.executeWithLock(lockStream, () -> {
                try {

                    gpgnetOut.writeMessage(command, args);
                    log.info(
                            "Sent GPGNet message: {} {}",
                            command,
                            Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
                } catch (IOException e) {
                    log.error("Error while communicating with FA (output), assuming shutdown", e);
                    GPGNetServer.this.onGpgnetConnectionLost();
                }
            });
        }

        /**
         * Listens for incoming messages from FA
         */
        private void listenerThread() {
            log.debug("Listening for GPG messages");
            boolean triggerActive =
                    false; // Prevents a race condition between this thread and the thread that has created this object
            // and is now going to set GPGNetServer.currentClient
            try (var inputStream = socket.getInputStream();
                    var gpgnetIn = new FaDataInputStream(inputStream)) {
                while (!Thread.currentThread().isInterrupted()
                        && (!triggerActive || currentClient == this)
                        && !stopping) {
                    String command = gpgnetIn.readString();
                    List<Object> args = gpgnetIn.readChunks();

                    processGpgnetMessage(command, args);

                    if (!triggerActive && currentClient != null) {
                        triggerActive =
                                true; // From now on we will check GPGNetServer.currentClient to see if we should stop
                    }
                }
            } catch (IOException e) {
                log.error("Error while communicating with FA (input), assuming shutdown", e);
                GPGNetServer.this.onGpgnetConnectionLost();
            }
            log.debug("No longer listening for GPGPNET from FA");
        }

        public void close() {
            stopping = true;
            this.listenerThread.interrupt();
            log.debug("Closing GPGNetClient");

            try {
                socket.close();
            } catch (IOException e) {
                log.error("Error while closing GPGNetClient socket", e);
            }
        }
    }

    /**
     * Closes all connections to the current client, removes this client.
     * To be called on encountering an error during the communication with the game instance
     * or on receiving an incoming connection request while still connected to a different instance.
     * THIS TRIGGERS A DISCONNECT FROM ALL PEERS AND AN ICE SHUTDOWN.
     */
    private void onGpgnetConnectionLost() {
        log.info("GPGNet connection lost");
        LockUtil.executeWithLock(lockSocket, () -> {
            if (currentClient != null) {
                currentClient.close();
                currentClient = null;

                if (clientFuture.isDone()) {
                    clientFuture = new CompletableFuture<>();
                }

                rpcService.onConnectionStateChanged("Disconnected");

                IceAdapter.onFAShutdown();
            }
        });
        debug().gpgnetConnectedDisconnected();
    }

    /**
     * Listens for incoming connections from a game instance
     */
    private void acceptThread() {
        while (!Thread.currentThread().isInterrupted()) {
            log.info("Listening for incoming connections from game");
            try {
                // The socket declaration must not be moved into a try-with-resources block, as the socket must not be
                // closed. It is passed into the GPGNetClient.
                Socket socket = serverSocket.accept();

                LockUtil.executeWithLock(lockSocket, () -> {
                    if (currentClient != null) {
                        onGpgnetConnectionLost();
                    }

                    currentClient = new GPGNetClient(socket);
                    clientFuture.complete(currentClient);

                    debug().gpgnetConnectedDisconnected();
                });
            } catch (SocketException e) {
                log.error("Game thread socket crashed", e);
                // TODO: Clarify
                // If we return here, why do we have the code in a while loop?
                // We could also not return and try to reconnect?
                return;
            } catch (IOException e) {
                log.error("Could not listen on socket", e);
            }
        }
    }

    /**
     * @return whether the game is connected via GPGNET
     */
    public static boolean isConnected() {
        return INSTANCE.currentClient != null;
    }

    public static String getGameStateString() {
        return getGameState().map(GameState::getName).orElse("");
    }

    public static Optional<GameState> getGameState() {
        return Optional.ofNullable(INSTANCE.currentClient).map(GPGNetClient::getGameState);
    }

    public static int getGpgnetPort() {
        return INSTANCE.gpgnetPort;
    }

    public static int getLobbyPort() {
        return INSTANCE.lobbyPort;
    }

    /**
     * Stops the GPGNetServer and thereby the connection to a currently connected client
     */
    public void close() {
        if (currentClient != null) {
            currentClient.close();
            currentClient = null;
            clientFuture = new CompletableFuture<>();
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.error("Could not close gpgnet server socket", e);
            }
        }
        log.info("GPGNetServer stopped");
    }
}
