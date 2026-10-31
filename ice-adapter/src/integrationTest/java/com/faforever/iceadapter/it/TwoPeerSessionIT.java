package com.faforever.iceadapter.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

/**
 * Two adapters and two games on one machine, with the test in the FAF client's place: it relays each
 * adapter's ICE messages to the other, as the lobby server would. No ICE servers are set, so the peers
 * connect over host candidates and the run needs no network beyond the machine itself.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TwoPeerSessionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int GAME_ID = 4711;

    @Test
    void peersConnectAndForwardGameTrafficBothWays(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess host = IceAdapterProcess.start(1, "alice", GAME_ID, logDir);
                IceAdapterProcess joiner = IceAdapterProcess.start(2, "bob", GAME_ID, logDir)) {
            relayIceMessages(host, joiner);
            relayIceMessages(joiner, host);

            try (MockGameProcess hostGame = host.startGame(logDir);
                    MockGameProcess joinerGame = joiner.startGame(logDir)) {
                host.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);
                joiner.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);

                // The client's order for a custom game: host, then join, then the host connects back.
                host.rpc.call("hostGame", "monument_valley.v0001");
                joiner.rpc.call("joinGame", host.login, host.playerId);
                host.rpc.call("connectToPeer", joiner.login, joiner.playerId, true);

                awaitConnected(host, joiner);
                awaitConnected(joiner, host);

                // 10 datagrams is a second of the game's 100 ms cadence, all through the ICE tunnel.
                assertThat(hostGame.awaitTrafficFrom(joiner.playerId, 10, TIMEOUT))
                        .isGreaterThanOrEqualTo(10);
                assertThat(joinerGame.awaitTrafficFrom(host.playerId, 10, TIMEOUT))
                        .isGreaterThanOrEqualTo(10);

                assertRelayConnected(host, joiner, true);
                assertRelayConnected(joiner, host, false);
            }
        }
    }

    private static void relayIceMessages(IceAdapterProcess from, IceAdapterProcess to) {
        from.rpc.onNotification(n -> {
            if (!n.method().equals("onIceMsg")) {
                return;
            }
            Thread.startVirtualThread(() -> {
                try {
                    // params: localPlayerId, remotePlayerId, msg
                    to.rpc.call("iceMsg", from.playerId, n.params().get(2).asText());
                } catch (Exception e) {
                    throw new IllegalStateException("Relaying ICE message " + from.login + " -> " + to.login, e);
                }
            });
        });
    }

    private static void awaitConnected(IceAdapterProcess local, IceAdapterProcess remote) throws Exception {
        local.rpc.await(
                "onConnected " + local.playerId + " -> " + remote.playerId,
                n -> n.method().equals("onConnected")
                        && n.params().get(1).asInt() == remote.playerId
                        && n.params().get(2).asBoolean(),
                TIMEOUT);
    }

    private static void assertRelayConnected(IceAdapterProcess local, IceAdapterProcess remote, boolean offerer)
            throws Exception {
        JsonNode relays = local.rpc.status().path("relays");
        assertThat(relays).hasSize(1);
        JsonNode relay = relays.get(0);
        assertThat(relay.path("remote_player_id").asInt()).isEqualTo(remote.playerId);
        assertThat(relay.path("remote_player_login").asText()).isEqualTo(remote.login);
        assertThat(relay.path("ice").path("offerer").asBoolean()).isEqualTo(offerer);
        assertThat(relay.path("ice").path("connected").asBoolean()).isTrue();
        assertThat(relay.path("ice").path("loc_cand_type").asText()).isEqualTo("host");
    }
}
