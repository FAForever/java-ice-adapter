package com.faforever.iceadapter.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The adapter jar under test, started the way the FAF client starts it, with an RPC client attached. */
final class IceAdapterProcess implements AutoCloseable {

    final int playerId;
    final String login;
    final int rpcPort;
    final int gpgNetPort;
    final int lobbyPort;
    final ManagedProcess process;
    final RpcClient rpc;

    private IceAdapterProcess(
            int playerId,
            String login,
            int rpcPort,
            int gpgNetPort,
            int lobbyPort,
            ManagedProcess process,
            RpcClient rpc) {
        this.playerId = playerId;
        this.login = login;
        this.rpcPort = rpcPort;
        this.gpgNetPort = gpgNetPort;
        this.lobbyPort = lobbyPort;
        this.process = process;
        this.rpc = rpc;
    }

    static IceAdapterProcess start(int playerId, String login, int gameId, Path logDir) throws Exception {
        Files.createDirectories(logDir);
        Path logbackConfig = logDir.resolve("logback-headless.xml");
        try (InputStream config = IceAdapterProcess.class.getResourceAsStream("/logback-headless.xml")) {
            Files.copy(config, logbackConfig, StandardCopyOption.REPLACE_EXISTING);
        }

        int rpcPort = ManagedProcess.freeTcpPort();
        int gpgNetPort = ManagedProcess.freeTcpPort();
        int lobbyPort = ManagedProcess.freeUdpPort();
        // Nothing listens here, so telemetry fails fast and the run stays off the network.
        int telemetryPort = ManagedProcess.freeTcpPort();

        ManagedProcess process = new ManagedProcess(
                "ice-adapter-" + login,
                List.of(
                        "-Djava.awt.headless=true",
                        "-Dlogback.configurationFile=" + logbackConfig.toUri(),
                        "-jar",
                        IntegrationTestSupport.iceAdapterJar().toString(),
                        "--id=" + playerId,
                        "--login=" + login,
                        "--game-id=" + gameId,
                        "--rpc-port=" + rpcPort,
                        "--gpgnet-port=" + gpgNetPort,
                        "--lobby-port=" + lobbyPort,
                        "--telemetry-server=ws://127.0.0.1:" + telemetryPort),
                // One directory per adapter: two adapters of a session would otherwise share ice-adapter.log.
                Map.of("LOG_DIR", logDir.resolve("ice-adapter-" + login).toString()),
                logDir);
        try {
            RpcClient rpc = RpcClient.connect(rpcPort, Duration.ofSeconds(30));
            return new IceAdapterProcess(playerId, login, rpcPort, gpgNetPort, lobbyPort, process, rpc);
        } catch (IOException | RuntimeException e) {
            process.close();
            throw e;
        }
    }

    /** Starts a mock game connected to this adapter's GPGNet port. */
    MockGameProcess startGame(Path logDir) throws IOException {
        return MockGameProcess.start(this, logDir);
    }

    @Override
    public void close() throws Exception {
        try {
            rpc.close();
        } finally {
            process.close();
        }
    }
}
