package com.faforever.iceadapter.it;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A java subprocess whose combined output goes to a log file, so it can be asserted on while running and
 * kept as CI evidence afterwards.
 */
class ManagedProcess implements AutoCloseable {

    private static final int PORT_RANGE_START = 20000;
    private static final int PORT_RANGE_END = 32000;
    private static final Set<Integer> HANDED_OUT = ConcurrentHashMap.newKeySet();

    private final String name;
    private final Process process;
    private final Path logFile;

    ManagedProcess(String name, List<String> javaArgs, Map<String, String> env, Path logDir) throws IOException {
        this.name = name;
        Files.createDirectories(logDir);
        this.logFile = logDir.resolve(name + ".log");

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(javaArgs);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(logDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        builder.environment().putAll(env);
        Files.writeString(logFile, "");
        this.process = builder.start();
    }

    /** Waits for an output line matching {@code regex} and returns its match. */
    Matcher awaitLine(String regex, Duration timeout) throws IOException, InterruptedException, TimeoutException {
        Pattern pattern = Pattern.compile(regex);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<Matcher> match = findLine(pattern);
            if (match.isPresent()) {
                return match.get();
            }
            if (!process.isAlive()) {
                // It may have logged the line and exited after the read above.
                match = findLine(pattern);
                if (match.isPresent()) {
                    return match.get();
                }
                throw new IllegalStateException(name + " exited with " + process.exitValue() + " before logging /"
                        + regex + "/; see " + logFile);
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException(
                        name + " did not log /" + regex + "/ within " + timeout + "; see " + logFile);
            }
            Thread.sleep(100);
        }
    }

    Optional<Matcher> findLine(Pattern pattern) throws IOException {
        return lines().stream().map(pattern::matcher).filter(Matcher::find).reduce((first, last) -> last);
    }

    List<String> lines() throws IOException {
        return Files.readAllLines(logFile, StandardCharsets.UTF_8);
    }

    Path logFile() {
        return logFile;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** Waits for the process to exit on its own; returns whether it did within {@code timeout}. */
    boolean awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Kills the process without letting it shut down, as a crash would. */
    void kill() throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** A TCP port that is free now and has not been handed out before in this run. */
    static int freeTcpPort() throws IOException {
        return freePort(port -> {
            try (ServerSocket socket = new ServerSocket(port)) {
                return true;
            }
        });
    }

    /** A UDP port that is free now and has not been handed out before in this run. */
    static int freeUdpPort() throws IOException {
        return freePort(port -> {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                return true;
            }
        });
    }

    private interface PortProbe {
        boolean bind(int port) throws IOException;
    }

    /**
     * The port is released before the subprocess binds it. An OS-assigned port lies in the ephemeral range,
     * where any outgoing connection can take it in the meantime (seen on the Linux runner), so the ports
     * come from below every platform's ephemeral range (Linux 32768+, Windows and macOS 49152+) and above the
     * adapter's own ICE range (6112-7112).
     */
    private static int freePort(PortProbe probe) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(PORT_RANGE_START, PORT_RANGE_END);
            if (!HANDED_OUT.add(port)) {
                continue;
            }
            try {
                if (probe.bind(port)) {
                    return port;
                }
            } catch (IOException e) {
                // In use; try another.
            }
        }
        throw new IOException("No free port in " + PORT_RANGE_START + "-" + PORT_RANGE_END);
    }
}
