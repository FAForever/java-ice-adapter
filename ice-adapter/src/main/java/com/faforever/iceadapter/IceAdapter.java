package com.faforever.iceadapter;

import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.PeerIceModule;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.Executor;
import com.faforever.iceadapter.util.TrayIconWrapper;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.util.concurrent.Callable;

import static com.faforever.iceadapter.debug.Debug.debug;

@CommandLine.Command(name = "faf-ice-adapter", mixinStandardHelpOptions = true, usageHelpAutoWidth = true,
        description = "An ice (RFC 5245) based network bridge between FAF client and ForgedAlliance.exe")
@Slf4j
public class IceAdapter implements Callable<Integer>, FafRpcCallbacks {
    private static IceAdapter INSTANCE;

    @CommandLine.ArgGroup(exclusive = false)
    private IceOptions iceOptions;

    private GPGNetServer gpgNetServer;
    private RPCService rpcService;
    private TrayIconWrapper trayIconWrapper;

    public static String VERSION = "SNAPSHOT";

    public static volatile GameSession gameSession;

    public static void main(String[] args) {
        new CommandLine(new IceAdapter()).execute(args);
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

        Debug.DELAY_UI_MS = iceOptions.getDelayUi();
        Debug.ENABLE_DEBUG_WINDOW = iceOptions.isDebugWindow();
        Debug.ENABLE_INFO_WINDOW = iceOptions.isInfoWindow();
        Debug.init();

        gpgNetServer = new GPGNetServer();
        rpcService = new RPCService();
        trayIconWrapper = new TrayIconWrapper();

        PeerIceModule.init(iceOptions.isForceRelay(), rpcService, trayIconWrapper);
        gpgNetServer.init(iceOptions.getGpgnetPort(), iceOptions.getLobbyPort(), rpcService);
        rpcService.init(iceOptions.getRpcPort(), gpgNetServer,this);

        debug().startupComplete();
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
        createGameSession();
        int port = gameSession.connectToPeer(remotePlayerLogin, remotePlayerId, false);

        sendToGpgNet("JoinGame", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
    }

    @Override
    public void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        if (gpgNetServer.isConnected() && gpgNetServer.getGameState().isPresent() && (gpgNetServer.getGameState().get() == GameState.LAUNCHING || gpgNetServer.getGameState().get() == GameState.ENDED)) {
            log.warn("Game ended or in progress, ABORTING connectToPeer");
            return;
        }

        log.info("onConnectToPeer {} {}, offer: {}", remotePlayerId, remotePlayerLogin, String.valueOf(offer));
        int port = gameSession.connectToPeer(remotePlayerLogin, remotePlayerId, offer);

        sendToGpgNet("ConnectToPeer", "127.0.0.1:" + port, remotePlayerLogin, remotePlayerId);
    }

    @Override
    public void onDisconnectFromPeer(int remotePlayerId) {
        log.info("onDisconnectFromPeer {}", remotePlayerId);
        gameSession.disconnectFromPeer(remotePlayerId);

        sendToGpgNet("DisconnectFromPeer", remotePlayerId);
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
        if (gameSession != null) {
            log.info("FA SHUTDOWN, closing everything");
            gameSession.close();
            gameSession = null;
            //Do not put code outside of this if clause, else it will be executed multiple times
        }
    }

    /**
     * Stop the ICE adapter
     */
    @Override
    public void close() {
        log.info("close() - stopping the adapter");

        Executor.executeDelayed(500, () -> System.exit(0));

        onFAShutdown();//will close gameSession aswell
        gpgNetServer.close();
        rpcService.close();
        trayIconWrapper.close();

        System.exit(0);
    }

    @Override
    public void sendToGpgNet(String header, Object... args) {
        gpgNetServer.sendToGpgNet(header, args);
    }

    public static int getId() {
        return INSTANCE.iceOptions.getId();
    }

    public static int getGameId() {
        return INSTANCE.iceOptions.getGameId();
    }

    public static String getLogin() {
        return INSTANCE.iceOptions.getLogin();
    }

    public static String getTelemetryServer() {
        return INSTANCE.iceOptions.getTelemetryServer();
    }

    public static int getPingCount() {
        return INSTANCE.iceOptions.getPingCount();
    }

    public static double getAcceptableLatency() {
        return INSTANCE.iceOptions.getAcceptableLatency();
    }

    private void determineVersion() {
        String versionFromGradle = getClass().getPackage().getImplementationVersion();
        if (versionFromGradle != null) {
            VERSION = versionFromGradle;
        }
    }
}
