package com.faforever.iceadapter.gpgnet;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.NetworkToolbox;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;

@Slf4j
public class GPGNetServer {
    private static GPGNetServer INSTANCE;

    private int gpgnetPort;
    private int lobbyPort;
    private RPCService rpcService;
    private ServerSocket serverSocket;
    private volatile GPGNetClient currentClient;

    //Used by other services to get a callback on FA connecting
    private volatile CompletableFuture<GPGNetClient> clientFuture = new CompletableFuture<>();

    public void sendToGpgNet(String header, Object... args) {
        clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage(header, args);
            });
        });
    }

    private volatile LobbyInitMode lobbyInitMode = LobbyInitMode.NORMAL;

    public static LobbyInitMode getLobbyInitMode() {
        return INSTANCE.lobbyInitMode;
    }

    public static void setLobbyInitMode(LobbyInitMode mode) {
        INSTANCE.lobbyInitMode = mode;
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
            serverSocket = new ServerSocket(GPGNetServer.getGpgnetPort());
        } catch (IOException e) {
            log.error("Couldn't start GPGNetServer", e);
            System.exit(-1);
        }

        new Thread(this::acceptThread).start();
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
        private final CompletableFuture<GPGNetClient> lobbyFuture = new CompletableFuture<>();

        private GPGNetClient(Socket socket) {
            this.socket = socket;

            try {
                gpgnetOut = new FaDataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                log.error("Could not create GPGNet output steam to FA", e);
            }

            listenerThread = new Thread(this::listenerThread);
            listenerThread.start();

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
                        sendGpgnetMessage("CreateLobby", lobbyInitMode.getId(), GPGNetServer.getLobbyPort(), IceAdapter.getLogin(), IceAdapter.getId(), 1);
                    } else if (gameState == GameState.LOBBY) {
                        lobbyFuture.complete(this);
                    }

                    debug().gameStateChanged();
                }
                case "GameEnded" -> {
                    if (IceAdapter.gameSession != null) {
                        IceAdapter.gameSession.setGameEnded(true);
                        log.info("GameEnded received, stopping reconnects...");
                    }
                }
                default -> {
                    //No need to log, as we are not processing all messages but just forward them via RPC
                }
            }

            log.info("Received GPGNet message: {} {}", command, args.stream().map(Object::toString).collect(Collectors.joining(" ")));
            rpcService.onGpgNetMessageReceived(command, args);
        }

        /**
         * Send a message to this FA instance via GPGNet
         */
        public synchronized void sendGpgnetMessage(String command, Object... args) {
            try {
                gpgnetOut.writeMessage(command, args);
                log.info("Sent GPGNet message: {} {}", command, Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
            } catch (IOException e) {
                log.error("Error while communicating with FA (output), assuming shutdown", e);
                GPGNetServer.this.onGpgnetConnectionLost();
            }
        }

        /**
         * Listens for incoming messages from FA
         */
        private void listenerThread() {
            log.debug("Listening for GPG messages");
            boolean triggerActive = false;//Prevents a race condition between this thread and the thread that has created this object and is now going to set GPGNetServer.currentClient
            try {
                FaDataInputStream gpgnetIn = new FaDataInputStream(socket.getInputStream());

                while ((!triggerActive || currentClient == this) && !stopping) {
                    String command = gpgnetIn.readString();
                    List<Object> args = gpgnetIn.readChunks();

                    processGpgnetMessage(command, args);

                    if (!triggerActive && currentClient != null) {
                        triggerActive = true;//From now on we will check currentClient to see if we should stop
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
        synchronized (serverSocket) {
            if (currentClient != null) {
                currentClient.close();
                currentClient = null;

                if (clientFuture.isDone()) {
                    clientFuture = new CompletableFuture<>();
                }

                rpcService.onConnectionStateChanged("Disconnected");

                IceAdapter.onFAShutdown();
            }
        }
        debug().gpgnetConnectedDisconnected();
    }

    /**
     * Listens for incoming connections from a game instance
     */
    private void acceptThread() {
        while (true) {
            log.info("Listening for incoming connections from game");
            try(Socket socket = serverSocket.accept()) {
                synchronized (serverSocket) {
                    if (currentClient != null) {
                        onGpgnetConnectionLost();
                    }

                    currentClient = new GPGNetClient(socket);
                    clientFuture.complete(currentClient);

                    debug().gpgnetConnectedDisconnected();

                    log.info("Disconnected from game");
                }
            } catch (SocketException e) {
                log.error("Game thread socket crashed", e);
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
            currentClient = null;
            currentClient.close();
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
