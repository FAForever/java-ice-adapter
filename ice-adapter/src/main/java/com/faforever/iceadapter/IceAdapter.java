package com.faforever.iceadapter;

import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.Executor;
import com.faforever.iceadapter.util.TrayIcon;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;
@CommandLine.Command(name = "faf-ice-adapter", mixinStandardHelpOptions = true, usageHelpAutoWidth = true,
        description = "An ice (RFC 5245) based network bridge between FAF client and ForgedAlliance.exe")
@Slf4j
public class IceAdapter implements Callable<Integer> {
    @CommandLine.ArgGroup(exclusive = false)
    private IceOptions iceOptions;

    public static boolean ALLOW_HOST = true;
    public static boolean ALLOW_REFLEXIVE = true;
    public static boolean ALLOW_RELAY = true;

    public static volatile boolean running = true;

    public static String VERSION = "SNAPSHOT";

    public static int id = -1;
    public static int gameId = -1;
    public static String login;
    public static int RPC_PORT;
    public static int GPGNET_PORT = 0;
    public static int LOBBY_PORT = 0;

    public static int PING_COUNT = 1;
    public static double ACCEPTABLE_LATENCY = 250.0;

    public static volatile GameSession gameSession;

    public static void main(String[] args) {
        new CommandLine(new IceAdapter()).execute(args);
    }

    @Override
    public Integer call() {
        IceAdapter.start(iceOptions);
        return 0;
    }

    public static void start(IceOptions iceOptions) {
        determineVersion();

        loadOptions(iceOptions);

        TrayIcon.create();

        //Configure file appender
//		RollingFileAppender fileAppender = (ch.qos.logback.core.rolling.RollingFileAppender)((ch.qos.logback.classic.Logger)log).getAppender("FILE");
//        if (logDirectory != null) {
//            Util.mkdir(Paths.get(logDirectory).toFile());
//			//TODO: set log dir
//        } else {
////			fileAppender.stop();
//		}

        log.info("Version: {}", VERSION);

        GPGNetServer.init();
        RPCService.init();

        debug().startupComplete();
    }

    public static void onHostGame(String mapName) {
        log.info("onHostGame");
        createGameSession();

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("HostGame", mapName);
            });
        });
    }

    public static void onJoinGame(String remotePlayerLogin, int remotePlayerId) {
        log.info("onJoinGame {} {}", remotePlayerId, remotePlayerLogin);
        createGameSession();
        int port = gameSession.connectToPeer(remotePlayerLogin, remotePlayerId, false);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("JoinGame", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
            });
        });
    }

    public static void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        if(GPGNetServer.isConnected() && GPGNetServer.getGameState().isPresent() && (GPGNetServer.getGameState().get() == GameState.LAUNCHING || GPGNetServer.getGameState().get() == GameState.ENDED)) {
            log.warn("Game ended or in progress, ABORTING connectToPeer");
            return;
        }

        log.info("onConnectToPeer {} {}, offer: {}", remotePlayerId, remotePlayerLogin, String.valueOf(offer));
        int port = gameSession.connectToPeer(remotePlayerLogin, remotePlayerId, offer);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("ConnectToPeer", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
            });
        });
    }

    public static void onDisconnectFromPeer(int remotePlayerId) {
        log.info("onDisconnectFromPeer {}", remotePlayerId);
        gameSession.disconnectFromPeer(remotePlayerId);

        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage("DisconnectFromPeer", remotePlayerId);
            });
        });
    }

    private synchronized static void createGameSession() {
        if (gameSession != null) {
            gameSession.close();
            gameSession = null;
        }

        gameSession = new GameSession();
    }

    /**
     * Triggered by losing gpgnet connection to FA.
     * Closes the active Game/ICE session
     */
    public synchronized static void onFAShutdown() {
        if(gameSession != null) {
            log.info("FA SHUTDOWN, closing everything");
            gameSession.close();
            gameSession = null;
            //Do not put code outside of this if clause, else it will be executed multiple times
        }
    }

    /**
     * Stop the ICE adapter
     */
    public static void close() {
        log.info("close() - stopping the adapter");

        Executor.executeDelayed(500, () -> System.exit(0));

        onFAShutdown();//will close gameSession aswell
        GPGNetServer.close();
        RPCService.close();
        TrayIcon.close();

        System.exit(0);
    }


    /**
     * Read command line arguments and set global, constant values
     * @param arguments The arguments to be read
     */
    public static void loadOptions(IceOptions iceOptions) {
        id = iceOptions.getId();
        gameId = iceOptions.getGameId();
        login = iceOptions.getLogin();
        RPC_PORT = iceOptions.getRpcPort();
        GPGNET_PORT = iceOptions.getGpgnetPort();
        LOBBY_PORT = iceOptions.getLobbyPort();

        if(iceOptions.isForceRelay()) {
            ALLOW_HOST = false;
            ALLOW_REFLEXIVE = false;
            ALLOW_RELAY = true;
        }

        Debug.DELAY_UI_MS = iceOptions.getDelayUi();
        PING_COUNT = iceOptions.getPingCount();
        ACCEPTABLE_LATENCY = iceOptions.getAcceptableLatency();

        Debug.ENABLE_DEBUG_WINDOW = iceOptions.isDebugWindow();
        Debug.ENABLE_INFO_WINDOW = iceOptions.isInfoWindow();
        Debug.init();
    }

    private static void determineVersion() {
        String versionFromGradle = IceAdapter.class.getPackage().getImplementationVersion();
        if(versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
