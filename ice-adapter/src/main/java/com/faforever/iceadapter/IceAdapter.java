package com.faforever.iceadapter;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.debug.TelemetryDebugger;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.RpcConnection;
import com.faforever.iceadapter.services.impl.rpc.RpcConnectionImpl;
import com.faforever.iceadapter.util.TrayIcon;
import com.faforever.iceadapter.webrtc.WebRtcConnectionFactory;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

@CommandLine.Command(
        name = "faf-ice-adapter",
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "An ice (RFC 5245) based network bridge between FAF client and ForgedAlliance.exe")
@Slf4j
public class IceAdapter implements Callable<Integer>, AutoCloseable, FafRpcCallbacks {
    public static volatile IceAdapter INSTANCE;
    private static String VERSION = "SNAPSHOT";

    @CommandLine.ArgGroup(exclusive = false)
    @Getter
    @Setter
    private IceOptions iceOptions;

    @Getter
    private GPGNetServer gpgNetServer;

    @Getter
    private RPCService rpcService;

    @Getter
    private WebRtcConnectionFactory webRtcConnectionFactory;

    @Getter
    @Setter
    private GameSession gameSession;

    public static void main(String[] args) {
        new CommandLine(new IceAdapter()).setUnmatchedArgumentsAllowed(true).execute(args);
    }

    @Override
    public Integer call() {
        INSTANCE = this;

        start();
        return 0;
    }

    public void start() {
        determineVersion();
        log.info("Version: {}", VERSION);

        startWebRtcMode();

        gpgNetServer = new GPGNetServer(iceOptions.getGpgnetPort(), iceOptions.getLobbyPort());
        rpcService = new RPCService(iceOptions.getRpcPort());
        gpgNetServer.init(this, rpcService);
        rpcService.init(gpgNetServer, this);

        var telemetryDebugger = new TelemetryDebugger(
                gpgNetServer, iceOptions.getTelemetryServer(), iceOptions.getGameId(), iceOptions.getId());
        Debug.register(telemetryDebugger);

        Debug.DELAY_UI_MS = iceOptions.getDelayUi();
        Debug.ENABLE_DEBUG_WINDOW = iceOptions.isDebugWindow();
        Debug.ENABLE_INFO_WINDOW = iceOptions.isInfoWindow();
        Debug.init();

        TrayIcon.create();

        registerShutdownHook();

        debug().startupComplete();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            log.info("JVM shutdown hook triggered");
                            IceAdapter instance = INSTANCE;
                            if (instance != null) {
                                try {
                                    instance.onFAShutdown();
                                } catch (Exception ignored) {
                                }
                                if (instance.webRtcConnectionFactory != null) {
                                    try {
                                        instance.webRtcConnectionFactory.shutdown();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        },
                        "ice-adapter-shutdown-hook"));
    }

    private void startWebRtcMode() {
        log.info("Starting in WEBRTC mode (WebRTC DataChannel)");
        webRtcConnectionFactory = WebRtcConnectionFactory.getInstance();
    }

    @Override
    public void onHostGame(String mapName) {
        log.info("onHostGame");
        createGameSession();
        sendToGpgNet("HostGame", mapName);
    }

    @Override
    public void onJoinGame(String remotePlayerLogin, int remotePlayerId) {
        log.info("onJoinGame {} {}", remotePlayerId, remotePlayerLogin);
        GameSession gs = createGameSession();

        AllowCombination combination = iceOptions.isForceRelay() ? AllowCombination.RELAY : AllowCombination.ALL;

        int port = gs.connectToPeer(remotePlayerLogin, remotePlayerId, false, 0, combination);
        sendToGpgNet("JoinGame", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
    }

    @Override
    public void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        if (gpgNetServer.isConnected()
                && gpgNetServer.getGameState().isPresent()
                && (gpgNetServer.getGameState().get() == GameState.LAUNCHING
                        || gpgNetServer.getGameState().get() == GameState.ENDED)) {
            log.warn("Game ended or in progress, ABORTING connectToPeer");
            return;
        }

        log.info("onConnectToPeer {} {}, offer: {}", remotePlayerId, remotePlayerLogin, offer);

        GameSession gs = getGameSession();
        if (gs == null) {
            log.warn("onConnectToPeer: no active GAME_SESSION, creating one");
            gs = createGameSession();
        }

        int port;
        AllowCombination combination = iceOptions.isForceRelay() ? AllowCombination.RELAY : AllowCombination.ALL;

        try {
            port = gs.connectToPeer(remotePlayerLogin, remotePlayerId, offer, 0, combination);
        } catch (RuntimeException e) {
            log.error("connectToPeer failed for {} {}: {}", remotePlayerId, remotePlayerLogin, e.toString());
            return;
        }

        sendToGpgNet("ConnectToPeer", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
    }

    @Override
    public void onDisconnectFromPeer(int remotePlayerId) {
        log.info("onDisconnectFromPeer {}", remotePlayerId);
        GameSession gs = getGameSessionSafe();
        if (gs != null) {
            gs.disconnectFromPeer(remotePlayerId);
        } else {
            log.warn("onDisconnectFromPeer: GAME_SESSION is null");
        }

        sendToGpgNet("DisconnectFromPeer", remotePlayerId);
    }

    private synchronized GameSession createGameSession() {
        GameSession gs = gameSession;
        if (gs != null) {
            try {
                gs.close();
            } catch (Exception e) {
                log.warn("Error closing previous GAME_SESSION", e);
            }
        }

        RpcConnection rpcConnection = new RpcConnectionImpl(rpcService);
        WebRtcConnectionFactory factory =
                webRtcConnectionFactory != null ? webRtcConnectionFactory : WebRtcConnectionFactory.getInstance();
        GameSession newGameSession = new GameSession(iceOptions, factory, rpcConnection);

        setGameSession(newGameSession);
        return newGameSession;
    }

    /**
     * Triggered by losing gpgnet connection to FA.
     * Closes the active Game/ICE session
     */
    public synchronized void onFAShutdown() {
        GameSession gs = gameSession;
        if (gs != null) {
            log.info("FA SHUTDOWN, closing everything");
            try {
                gs.close();
            } catch (Exception e) {
                log.warn("Error while closing GAME_SESSION during onFAShutdown", e);
            }
            gameSession = null;
        }
    }

    @Override
    public void close() {
        close(0);
    }

    /**
     * Stop the ICE adapter
     */
    public static void close(int status) {
        IceAdapter instance = INSTANCE;
        if (instance == null) {
            log.warn("close() called but INSTANCE is null");
            System.exit(status);
            return;
        }

        log.info("close() - stopping the adapter. Status: {}", status);

        instance.onFAShutdown(); // will close gameSession aswell

        try {
            if (instance.gpgNetServer != null) {
                instance.gpgNetServer.close();
            }
        } catch (Exception e) {
            log.warn("Error closing GPGNetServer", e);
        }
        try {
            if (instance.rpcService != null) {
                instance.rpcService.close();
            }
        } catch (Exception e) {
            log.warn("Error closing RPCService", e);
        }
        try {
            if (instance.webRtcConnectionFactory != null) {
                instance.webRtcConnectionFactory.shutdown();
            }
        } catch (Exception e) {
            log.warn("Error closing WebRtcConnectionFactory", e);
        }

        Debug.close();
        TrayIcon.close();

        System.exit(status);
    }

    @Override
    public void sendToGpgNet(String header, Object... args) {
        if (gpgNetServer != null) {
            gpgNetServer.sendToGpgNet(header, args);
        } else {
            log.warn("sendToGpgNet called but gpgNetServer is null: {}", header);
        }
    }

    public static int getId() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getId() : 0;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static int getGameId() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getGameId() : 0;
    }

    public static String getLogin() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getLogin() : "";
    }

    public static String getTelemetryServer() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getTelemetryServer() : null;
    }

    public static int getPingCount() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null ? instance.iceOptions.getPingCount() : 0;
    }

    public static double getAcceptableLatency() {
        IceAdapter instance = INSTANCE;
        return instance != null && instance.iceOptions != null
                ? instance.iceOptions.getAcceptableLatency()
                : Double.MAX_VALUE;
    }

    public static GameSession getGameSessionSafe() {
        IceAdapter instance = INSTANCE;
        if (instance == null) {
            return null;
        }
        return instance.gameSession;
    }

    private void determineVersion() {
        String versionFromGradle = getClass().getPackage().getImplementationVersion();
        if (versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
