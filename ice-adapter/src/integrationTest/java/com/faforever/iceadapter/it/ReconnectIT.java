package com.faforever.iceadapter.it;

import static com.faforever.iceadapter.it.PeerSessions.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

/**
 * A connected two-peer session that loses its connection and gets it back, with the test in the place of
 * both FAF clients and the lobby server.
 *
 * <p>Every recovery here is one a client or the server starts. Without ICE servers the peers only have host
 * candidates, and an ICE module that has already made one connection attempt in the past two minutes stops
 * offering them, so the adapter's own retries cannot succeed offline. A repeated {@code connectToPeer}
 * replaces the peer, and a fresh peer offers host candidates again.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ReconnectIT {

    private static final int GAME_ID = 4712;

    // The offerer's echo checker gives up on a peer after 10 s without an answer.
    private static final Duration LOSS_DETECTION = Duration.ofSeconds(30);

    // More than the 10 datagrams a game's progress line can lag behind, so some must have crossed the new
    // connection.
    private static final int DATAGRAMS_AFTER_RECONNECT = 20;

    @Test
    void repeatedConnectToPeerRebuildsTheConnection(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess host = IceAdapterProcess.start(1, "alice", GAME_ID, logDir);
                IceAdapterProcess joiner = IceAdapterProcess.start(2, "bob", GAME_ID, logDir)) {
            PeerSessions.relayIceMessages(host, () -> joiner);
            PeerSessions.relayIceMessages(joiner, () -> host);

            try (MockGameProcess hostGame = host.startGame(logDir);
                    MockGameProcess joinerGame = joiner.startGame(logDir)) {
                PeerSessions.connect(host, joiner);
                hostGame.awaitTrafficFrom(joiner.playerId, 10, TIMEOUT);
                joinerGame.awaitTrafficFrom(host.playerId, 10, TIMEOUT);

                int hostMark = host.rpc.mark();
                int joinerMark = joiner.rpc.mark();
                // Joiner first, so its fresh peer is already waiting when the host's new offer arrives.
                joiner.rpc.call("connectToPeer", host.login, host.playerId, false);
                host.rpc.call("connectToPeer", joiner.login, joiner.playerId, true);

                PeerSessions.awaitConnected(host, joiner, false, hostMark, TIMEOUT);
                PeerSessions.awaitConnected(joiner, host, false, joinerMark, TIMEOUT);
                PeerSessions.awaitConnected(host, joiner, true, hostMark, TIMEOUT);
                PeerSessions.awaitConnected(joiner, host, true, joinerMark, TIMEOUT);

                assertTrafficResumes(hostGame, joiner.playerId);
                assertTrafficResumes(joinerGame, host.playerId);
                PeerSessions.assertRelayConnected(host, joiner, true);
                PeerSessions.assertRelayConnected(joiner, host, false);
            }
        }
    }

    @Test
    void hostReconnectsToAJoinerThatCrashedAndRejoined(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);
        Path crashedDir = logDir.resolve("joiner-crashed");
        Path rejoinedDir = logDir.resolve("joiner-rejoined");

        try (IceAdapterProcess host = IceAdapterProcess.start(1, "alice", GAME_ID, logDir);
                IceAdapterProcess joiner = IceAdapterProcess.start(2, "bob", GAME_ID, crashedDir)) {
            AtomicReference<IceAdapterProcess> currentJoiner = new AtomicReference<>(joiner);
            PeerSessions.relayIceMessages(host, currentJoiner::get);
            PeerSessions.relayIceMessages(joiner, () -> host);

            try (MockGameProcess hostGame = host.startGame(logDir);
                    MockGameProcess joinerGame = joiner.startGame(crashedDir)) {
                PeerSessions.connect(host, joiner);
                hostGame.awaitTrafficFrom(joiner.playerId, 10, TIMEOUT);

                int lossMark = host.rpc.mark();
                currentJoiner.set(null);
                joinerGame.process.kill();
                joiner.process.kill();

                // Nobody tells the host; it has to notice the silence itself.
                PeerSessions.awaitConnected(host, joiner, false, lossMark, LOSS_DETECTION);
                host.process.awaitLine("Didn't receive any answer to echo requests", TIMEOUT);

                try (IceAdapterProcess rejoined = IceAdapterProcess.start(2, "bob", GAME_ID, rejoinedDir);
                        MockGameProcess rejoinedGame = rejoined.startGame(rejoinedDir)) {
                    PeerSessions.relayIceMessages(rejoined, () -> host);
                    rejoined.rpc.awaitGpgNetMessage("GameState", "Lobby", TIMEOUT);

                    // The lobby server's part when a player rejoins: the player joins the game again and the
                    // host is told to connect to it, which replaces the host's lost peer.
                    int rejoinMark = host.rpc.mark();
                    rejoined.rpc.call("joinGame", host.login, host.playerId);
                    currentJoiner.set(rejoined);
                    host.rpc.call("connectToPeer", rejoined.login, rejoined.playerId, true);

                    PeerSessions.awaitConnected(host, rejoined, true, rejoinMark, TIMEOUT);
                    PeerSessions.awaitConnected(rejoined, host, true, 0, TIMEOUT);

                    assertTrafficResumes(hostGame, rejoined.playerId);
                    assertThat(rejoinedGame.awaitTrafficFrom(host.playerId, 10, TIMEOUT))
                            .isGreaterThanOrEqualTo(10);
                    PeerSessions.assertRelayConnected(host, rejoined, true);
                    PeerSessions.assertRelayConnected(rejoined, host, false);
                }
            }
        }
    }

    @Test
    void gameCrashClosesThePeersAndARestartedGameIsAccepted(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess host = IceAdapterProcess.start(1, "alice", GAME_ID, logDir);
                IceAdapterProcess joiner = IceAdapterProcess.start(2, "bob", GAME_ID, logDir)) {
            PeerSessions.relayIceMessages(host, () -> joiner);
            PeerSessions.relayIceMessages(joiner, () -> host);

            try (MockGameProcess hostGame = host.startGame(logDir);
                    MockGameProcess joinerGame = joiner.startGame(logDir)) {
                PeerSessions.connect(host, joiner);
                hostGame.awaitTrafficFrom(joiner.playerId, 10, TIMEOUT);

                int crashMark = host.rpc.mark();
                hostGame.process.kill();

                host.rpc.awaitSince(
                        crashMark,
                        "onConnectionStateChanged Disconnected",
                        n -> n.method().equals("onConnectionStateChanged")
                                && n.params().get(0).asText().equals("Disconnected"),
                        TIMEOUT);
                PeerSessions.awaitConnected(host, joiner, false, crashMark, TIMEOUT);
                // The peer reports the disconnect while it closes; the session drops it right after.
                awaitNoRelays(host);

                // The adapter keeps listening on its GPGNet port, so the client can start the game again.
                try (MockGameProcess restartedGame = host.startGame(logDir.resolve("game-restarted"))) {
                    host.rpc.awaitSince(
                            crashMark,
                            "onConnectionStateChanged Connected",
                            n -> n.method().equals("onConnectionStateChanged")
                                    && n.params().get(0).asText().equals("Connected"),
                            TIMEOUT);
                    host.rpc.awaitGpgNetMessageSince(crashMark, "GameState", "Lobby", TIMEOUT);
                    restartedGame.process.awaitLine("lobby socket bound on port " + host.lobbyPort, TIMEOUT);
                }
            }
        }
    }

    private static void awaitNoRelays(IceAdapterProcess adapter) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!adapter.rpc.status().path("relays").isEmpty()) {
            if (System.nanoTime() > deadline) {
                assertThat(adapter.rpc.status().path("relays"))
                        .as("relays after the game crashed")
                        .isEmpty();
            }
            Thread.sleep(100);
        }
    }

    private static void assertTrafficResumes(MockGameProcess game, int senderId) throws Exception {
        long before = game.trafficFrom(senderId);
        assertThat(game.awaitTrafficFrom(senderId, before + DATAGRAMS_AFTER_RECONNECT, TIMEOUT))
                .isGreaterThanOrEqualTo(before + DATAGRAMS_AFTER_RECONNECT);
    }
}
