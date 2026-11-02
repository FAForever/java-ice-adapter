package com.faforever.iceadapter.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The FAF client's and the lobby server's part between two adapters, played by the test: relaying ICE
 * messages and issuing the host/join/connect calls of a custom game.
 */
final class PeerSessions {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private PeerSessions() {}

    /**
     * Relays {@code from}'s ICE messages to whichever adapter {@code to} names when one arrives. Messages
     * are dropped while it names none or one that is no longer running, as the lobby server drops them for
     * a player who left.
     */
    static void relayIceMessages(IceAdapterProcess from, Supplier<IceAdapterProcess> to) {
        from.rpc.onNotification(n -> {
            if (!n.method().equals("onIceMsg")) {
                return;
            }
            IceAdapterProcess target = to.get();
            if (target == null || !target.process.isAlive()) {
                return;
            }
            Thread.startVirtualThread(() -> {
                try {
                    // params: localPlayerId, remotePlayerId, msg
                    target.rpc.call("iceMsg", from.playerId, n.params().get(2).asText());
                } catch (Exception e) {
                    throw new IllegalStateException("Relaying ICE message " + from.login + " -> " + target.login, e);
                }
            });
        });
    }

    /**
     * Brings both games to the lobby and connects the peers in the client's order for a custom game: host,
     * then join, then the host connects back. Returns once both adapters report the connection.
     */
    static void connect(IceAdapterProcess host, IceAdapterProcess joiner) throws Exception {
        host.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);
        joiner.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);

        host.rpc.call("hostGame", "monument_valley.v0001");
        joiner.rpc.call("joinGame", host.login, host.playerId);
        host.rpc.call("connectToPeer", joiner.login, joiner.playerId, true);

        awaitConnected(host, joiner, true, 0, TIMEOUT);
        awaitConnected(joiner, host, true, 0, TIMEOUT);
    }

    /** Waits for {@code local} to report its connection to {@code remote} as up or down after {@code mark}. */
    static void awaitConnected(
            IceAdapterProcess local, IceAdapterProcess remote, boolean connected, int mark, Duration timeout)
            throws Exception {
        local.rpc.awaitSince(
                mark,
                "onConnected " + local.playerId + " -> " + remote.playerId + " " + connected,
                n -> n.method().equals("onConnected")
                        && n.params().get(1).asInt() == remote.playerId
                        && n.params().get(2).asBoolean() == connected,
                timeout);
    }

    static void assertRelayConnected(IceAdapterProcess local, IceAdapterProcess remote, boolean offerer)
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
