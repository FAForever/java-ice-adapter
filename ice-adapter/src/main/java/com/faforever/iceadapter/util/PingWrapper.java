package com.faforever.iceadapter.util;

import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/*
 * A wrapper around calling the system `ping` executable to query the latency of a host.
 */
@Slf4j
public class PingWrapper {
    static final Pattern WINDOWS_OUTPUT_PATTERN = Pattern.compile("Average = (\\d+)ms", Pattern.MULTILINE);
    static final Pattern GNU_OUTPUT_PATTERN =
            Pattern.compile("min/avg/max/mdev = [0-9.]+/([0-9.]+)/[0-9.]+/[0-9.]+", Pattern.MULTILINE);

    private static ExecutorService executor = Executors.newFixedThreadPool(4);

    /*
     * Get the round trip time to an address.
     */
    public static CompletableFuture<Double> getLatency(String address, Integer count) {
        try {
            Process process;
            Pattern output_pattern;

            if (System.getProperty("os.name").startsWith("Windows")) {
                // Force English output using code page 437
                String command = String.format("chcp 437 > NUL && ping -n %d %s", count, address);
                process = new ProcessBuilder("cmd", "/c", command).start();
                output_pattern = WINDOWS_OUTPUT_PATTERN;
            } else {
                process = new ProcessBuilder("ping", "-c", count.toString(), address).start();
                output_pattern = GNU_OUTPUT_PATTERN;
            }

            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            process.waitFor();
                            InputStreamReader reader = new InputStreamReader(process.getInputStream());
                            String output = CharStreams.toString(reader);
                            reader.close();

                            Matcher m = output_pattern.matcher(output);

                            if (m.find()) {
                                double result = Double.parseDouble(m.group(1));
                                log.debug("Pinged {} with an RTT of {}", address, result);
                                return result;
                            } else {
                                log.warn("Failed to ping {}: output='{}'", address, output);
                                throw new RuntimeException("Failed to contact the host or parse ping output");
                            }
                        } catch (InterruptedException | IOException | RuntimeException e) {
                            throw new CompletionException(e);
                        }
                    },
                    executor);
        } catch (IOException e) {
            CompletableFuture<Double> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
}
