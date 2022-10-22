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
    private static int GPGNET_PORT;
    private static int LOBBY_PORT;
    private static ServerSocket serverSocket;
    private static volatile GPGNetClient currentClient;

    //Used by other services to get a callback on FA connecting
    public static volatile CompletableFuture<GPGNetClient> clientFuture = new CompletableFuture<>();

    public static volatile LobbyInitMode lobbyInitMode = LobbyInitMode.NORMAL;


    public static void init(int gpgnetPort, int lobbyPort) {
        if (gpgnetPort == 0) {
            GPGNET_PORT = NetworkToolbox.findFreeTCPPort(20000, 65536);
            log.info("Generated GPGNET_PORT: {}", GPGNET_PORT);
        } else {
            GPGNET_PORT = gpgnetPort;
            log.info("Using GPGNET_PORT: {}", GPGNET_PORT);
        }

        if (lobbyPort == 0) {
            LOBBY_PORT = NetworkToolbox.findFreeUDPPort(20000, 65536);
            log.info("Generated LOBBY_PORT: {}", LOBBY_PORT);
        } else {
            LOBBY_PORT = lobbyPort;
            log.info("Using LOBBY_PORT: {}", LOBBY_PORT);
        }

        try {
            serverSocket = new ServerSocket(GPGNET_PORT);
        } catch (IOException e) {
            log.error("Couldn't start GPGNetServer", e);
            System.exit(-1);
        }

        new Thread(GPGNetServer::acceptThread).start();
        log.info("GPGNetServer started");
    }

    /**
     * Represents a client (a game instance) connected to this GPGNetServer
     */
    @Getter
    public static class GPGNetClient {
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

            RPCService.onConnectionStateChanged("Connected");
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
            RPCService.onGpgNetMessageReceived(command, args);
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
                GPGNetServer.onGpgnetConnectionLost();
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

                while ((!triggerActive || GPGNetServer.currentClient == this) && !stopping) {
                    String command = gpgnetIn.readString();
                    List<Object> args = gpgnetIn.readChunks();

                    processGpgnetMessage(command, args);

                    if (!triggerActive && GPGNetServer.currentClient != null) {
                        triggerActive = true;//From now on we will check GPGNetServer.currentClient to see if we should stop
                    }
                }
            } catch (IOException e) {
                log.error("Error while communicating with FA (input), assuming shutdown", e);
                GPGNetServer.onGpgnetConnectionLost();
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
    private static void onGpgnetConnectionLost() {
        log.info("GPGNet connection lost");
        synchronized (serverSocket) {
            if (currentClient != null) {
                currentClient.close();
                currentClient = null;

                if (clientFuture.isDone()) {
                    clientFuture = new CompletableFuture<>();
                }

                RPCService.onConnectionStateChanged("Disconnected");

                IceAdapter.onFAShutdown();
            }
        }
        debug().gpgnetConnectedDisconnected();
    }

    /**
     * Listens for incoming connections from a game instance
     */
    private static void acceptThread() {
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
        return currentClient != null;
    }

    public static String getGameStateString() {
        return getGameState().map(GameState::getName).orElse("");
    }

    public static Optional<GameState> getGameState() {
        return Optional.ofNullable(currentClient).map(GPGNetClient::getGameState);
    }

    public static int getGpgnetPort() {
        return GPGNET_PORT;
    }

    public static int getLobbyPort() {
        return LOBBY_PORT;
    }

    /**
     * Stops the GPGNetServer and thereby the connection to a currently connected client
     */
    public static void close() {
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
