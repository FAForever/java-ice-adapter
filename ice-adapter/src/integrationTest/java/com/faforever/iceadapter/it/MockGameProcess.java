package com.faforever.iceadapter.it;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;

/**
 * faf-test-harness's mock-game: speaks GPGNet to the adapter and, once told about a peer, sends it one
 * datagram every 100 ms with a sequence number.
 *
 * @see <a href="https://github.com/md-173/faf-test-harness/blob/main/mock-game/README.md">mock-game</a>
 */
final class MockGameProcess implements AutoCloseable {

    final ManagedProcess process;

    private MockGameProcess(ManagedProcess process) {
        this.process = process;
    }

    static MockGameProcess start(IceAdapterProcess adapter, Path logDir) throws IOException {
        String name = "mock-game-" + adapter.login;
        return new MockGameProcess(new ManagedProcess(
                name,
                List.of(
                        "-jar",
                        IntegrationTestSupport.mockGameJar().toString(),
                        "--gpgnet-port=" + adapter.gpgNetPort,
                        "--lobby-port=" + adapter.lobbyPort,
                        "--player-id=" + adapter.playerId,
                        "--player-login=" + adapter.login,
                        "--game-uid=0",
                        // No lobby server here to launch the match against; the game stays in the lobby,
                        // where peer traffic already flows.
                        "--launch-delay-seconds=-1",
                        "--lobby-timeout-seconds=120"),
                Map.of("LOG_FILE", logDir.resolve(name + ".jsonl").toString()),
                logDir));
    }

    /** Waits until this game has received at least {@code datagrams} datagrams from {@code senderId}. */
    long awaitTrafficFrom(int senderId, long datagrams, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        String progress =
                "peer traffic from player " + senderId + ": (\\d+) datagrams, highest sequence \\d+, gaps (\\d+)";
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Matcher match = process.awaitLine(progress, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            long received = Long.parseLong(match.group(1));
            if (received >= datagrams) {
                return received;
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("only " + received + " of " + datagrams + " datagrams from player "
                        + senderId + " arrived within " + timeout);
            }
            Thread.sleep(250);
        }
    }

    @Override
    public void close() throws InterruptedException {
        process.close();
    }
}
