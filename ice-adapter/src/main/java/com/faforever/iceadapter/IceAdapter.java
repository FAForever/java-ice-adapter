package com.faforever.iceadapter;

import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.ArgumentParser;
import com.faforever.iceadapter.util.Executor;
import com.faforever.iceadapter.util.TrayIcon;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;

@Slf4j
public class IceAdapter {
    public static boolean ALLOW_HOST = true;
    public static boolean ALLOW_REFLEXIVE = true;
    public static boolean ALLOW_RELAY = true;

    public static volatile boolean running = true;

    public static String VERSION = "SNAPSHOT";
    public static String COMMAND_LINE_ARGUMENTS;

    public static int id = -1;
    public static String login;
    public static int RPC_PORT;
    public static int GPGNET_PORT = 0;
    public static int LOBBY_PORT = 0;

    public static int PING_COUNT = 1;
    public static double ACCEPTABLE_LATENCY = 250.0;

    public static int GPGNET_SERVER_BIND_RETRY_COUNT = 3;
    public static int GPGNET_SERVER_BIND_RETRY_DELAY = 500;

    public static volatile GameSession gameSession;

    public static void main(String args[]) {
        determineVersion();

        COMMAND_LINE_ARGUMENTS = Arrays.stream(args).collect(Collectors.joining(" "));
        interpretArguments(ArgumentParser.parse(args));

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
    public static void interpretArguments(Map<String, String> arguments) {
        if(arguments.containsKey("help")) {
            System.out.println("faf-ice-adapter usage:\n" +
                    "--help                               produce help message\n" +
                    "--id arg                             set the ID of the local player\n" +
                    "--login arg                          set the login of the local player, e.g. \"Rhiza\"\n" +
                    "--rpc-port arg (=7236)               set the port of internal JSON-RPC server\n" +
                    "--gpgnet-port arg (=0)               set the port of internal GPGNet server\n" +
                    "--lobby-port arg (=0)                set the port the game lobby should use for incoming UDP packets from the PeerRelay\n" +
                    "--log-directory arg                  NOT SUPPORTED, use env variable LOG_DIR instead\n" +
                    "--force-relay                        force the usage of relay candidates only\n" +
                    "--debug-window                       activate the debug window if JavaFX is available\n" +
                    "--info-window                        activate the info window if JavaFX is available (allows access at the debug window)\n" +
                    "--delay-ui arg                       delays the launch of the info and debug window by arg ms\n" +
                    "--ping-count arg (=1)                number of times to ping each turn server to determine latency\n" +
                    "--acceptable-latency arg (=250.0)    if latency to the official FAF relay surpasses this threshold, search for closer relays");
            System.exit(0);
        }

        if(! Arrays.asList("id", "login").stream().allMatch(arguments::containsKey)) {
            log.error("Missing necessary argument.");
            System.exit(-1);
        }

        id = Integer.parseInt(arguments.get("id"));
        login = arguments.get("login");
        if(arguments.containsKey("rpc-port")) {
            RPC_PORT = Integer.parseInt(arguments.get("rpc-port"));
        }
        if(arguments.containsKey("gpgnet-port")) {
            GPGNET_PORT = Integer.parseInt(arguments.get("gpgnet-port"));
        }
        if(arguments.containsKey("lobby-port")) {
            LOBBY_PORT = Integer.parseInt(arguments.get("lobby-port"));
        }
        if(arguments.containsKey("log-directory")) {
            log.warn("--log-directory is not supported, set the desired log directory using the LOG_DIR env variable");
        }
        if(arguments.containsKey("force-relay")) {
            ALLOW_HOST = false;
            ALLOW_REFLEXIVE = false;
            ALLOW_RELAY = true;
        }
        if(arguments.containsKey("delay-ui")) {
            Debug.DELAY_UI_MS = Integer.parseInt(arguments.get("delay-ui"));
        }
        if(arguments.containsKey("ping-count")) {
            PING_COUNT = Integer.parseInt(arguments.get("ping-count"));
        }
        if(arguments.containsKey("acceptable-latency")) {
            ACCEPTABLE_LATENCY = Double.parseDouble(arguments.get("acceptable-latency"));
        }

        Debug.ENABLE_DEBUG_WINDOW = arguments.containsKey("debug-window");
        Debug.ENABLE_INFO_WINDOW = arguments.containsKey("info-window");
        Debug.init();
    }

    private static void determineVersion() {
        String versionFromGradle = IceAdapter.class.getPackage().getImplementationVersion();
        if(versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
