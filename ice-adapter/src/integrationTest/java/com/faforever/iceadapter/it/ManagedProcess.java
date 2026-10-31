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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A java subprocess whose combined output goes to a log file, so it can be asserted on while running and
 * kept as CI evidence afterwards.
 */
class ManagedProcess implements AutoCloseable {

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

    boolean isAlive() {
        return process.isAlive();
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

    static int freeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static int freeUdpPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
