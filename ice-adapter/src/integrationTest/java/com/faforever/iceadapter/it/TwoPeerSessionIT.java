package com.faforever.iceadapter.it;

import static com.faforever.iceadapter.it.PeerSessions.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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

    private static final int GAME_ID = 4711;

    @Test
    void peersConnectAndForwardGameTrafficBothWays(TestInfo testInfo) throws Exception {
        Path logDir = IntegrationTestSupport.logDir(testInfo);

        try (IceAdapterProcess host = IceAdapterProcess.start(1, "alice", GAME_ID, logDir);
                IceAdapterProcess joiner = IceAdapterProcess.start(2, "bob", GAME_ID, logDir)) {
            PeerSessions.relayIceMessages(host, () -> joiner);
            PeerSessions.relayIceMessages(joiner, () -> host);

            try (MockGameProcess hostGame = host.startGame(logDir);
                    MockGameProcess joinerGame = joiner.startGame(logDir)) {
                PeerSessions.connect(host, joiner);

                // 10 datagrams is a second of the game's 100 ms cadence, all through the ICE tunnel.
                assertThat(hostGame.awaitTrafficFrom(joiner.playerId, 10, TIMEOUT))
                        .isGreaterThanOrEqualTo(10);
                assertThat(joinerGame.awaitTrafficFrom(host.playerId, 10, TIMEOUT))
                        .isGreaterThanOrEqualTo(10);

                PeerSessions.assertRelayConnected(host, joiner, true);
                PeerSessions.assertRelayConnected(joiner, host, false);
            }
        }
    }
}
