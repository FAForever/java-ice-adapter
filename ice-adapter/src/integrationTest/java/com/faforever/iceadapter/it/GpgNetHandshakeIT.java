package com.faforever.iceadapter.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

/** One adapter, one game: the adapter boots headless, serves RPC and GPGNet, and relays the lobby handshake. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class GpgNetHandshakeIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void gameReachesLobbyThroughAdapter(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess adapter = IceAdapterProcess.start(1, "alice", 0, logDir);
                MockGameProcess game = adapter.startGame(logDir)) {
            adapter.rpc.await(
                    "onConnectionStateChanged Connected",
                    n -> n.method().equals("onConnectionStateChanged")
                            && n.params().get(0).asText().equals("Connected"),
                    TIMEOUT);
            adapter.rpc.awaitGpgNetMessage("GameState", "Idle", TIMEOUT);
            adapter.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);

            // The game binds the UDP port the adapter announced in CreateLobby.
            game.process.awaitLine("lobby socket bound on port " + adapter.lobbyPort, TIMEOUT);

            JsonNode status = adapter.rpc.status();
            assertThat(status.path("lobby_port").asInt()).isEqualTo(adapter.lobbyPort);
            assertThat(status.path("options").path("player_id").asInt()).isEqualTo(1);
            assertThat(status.path("options").path("player_login").asText()).isEqualTo("alice");
            assertThat(status.path("relays")).isEmpty();
        }
    }

    @Test
    void adapterStopsWhenClientConnectionResetsInLobby(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess adapter = IceAdapterProcess.start(1, "alice", 0, logDir);
                MockGameProcess game = adapter.startGame(logDir)) {
            adapter.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);
            adapter.rpc.abort();

            adapter.process.awaitLine("close\\(\\) - stopping the adapter", TIMEOUT);
        }
    }

    @Test
    @Disabled("JJsonPeer ends its read loop on a clean EOF without calling the onConnectionLost callback,"
            + " so the adapter outlives a client that closed its socket or died with a FIN")
    void adapterStopsWhenClientClosesConnectionInLobby(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess adapter = IceAdapterProcess.start(1, "alice", 0, logDir);
                MockGameProcess game = adapter.startGame(logDir)) {
            adapter.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);
            adapter.rpc.close();

            adapter.process.awaitLine("close\\(\\) - stopping the adapter", TIMEOUT);
        }
    }

    @Test
    @Disabled("RPCService's connection-lost handler calls getName() on an absent game state; the NPE is"
            + " swallowed and the adapter keeps running")
    void adapterStopsWhenClientConnectionResetsBeforeGameConnects(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess adapter = IceAdapterProcess.start(1, "alice", 0, logDir)) {
            adapter.rpc.status();
            adapter.rpc.abort();

            adapter.process.awaitLine("close\\(\\) - stopping the adapter", TIMEOUT);
        }
    }
}
