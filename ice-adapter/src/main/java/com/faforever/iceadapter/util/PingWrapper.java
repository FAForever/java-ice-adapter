package com.faforever.iceadapter.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.io.CharStreams;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;


/*
 * A wrapper around calling the system `ping` executable to query the latency of a host.
 */
@Slf4j
public class PingWrapper {
    static final Pattern WINDOWS_OUTPUT_PATTERN = Pattern.compile("Average = (\\d+)ms", Pattern.MULTILINE);
    static final Pattern UNIX_OUTPUT_PATTERN = Pattern.compile("min/avg/max/mdev = [0-9.]+/([0-9.]+)/[0-9.]+/[0-9.]+", Pattern.MULTILINE);

    /*
     * Get the round trip time to an address.
     */
    public static CompletableFuture<Double> getRTT(String address) {
        try {
            Process process;
            Pattern output_pattern;

            if (SystemUtils.IS_OS_WINDOWS) {
                process = new ProcessBuilder("ping", "-n", "1", address).start();
                output_pattern = WINDOWS_OUTPUT_PATTERN;
            } else if (SystemUtils.IS_OS_UNIX) {
                process = new ProcessBuilder("ping", "-c", "1", address).start();
                output_pattern = UNIX_OUTPUT_PATTERN;
            } else {
                throw new UnsupportedOperationException("Unsupported operating system");
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    process.waitFor();
                    InputStreamReader reader = new InputStreamReader(process.getInputStream());
                    String output = CharStreams.toString(reader);
                    reader.close();

                    Matcher m = output_pattern.matcher(output);

                    if (m.find()) {
                        double result = Double.parseDouble(m.group(1));
                        log.debug(String.format("Pinged %s with an RTT of %f", address, result));
                        return result;
                    } else {
                        log.warn(String.format("Failed to ping %s", address));
                        throw new RuntimeException("Failed to contact the host");
                    }
                } catch (InterruptedException | IOException | RuntimeException e) {
                    throw new CompletionException(e);
                }
            });
        } catch (IOException e) {
            CompletableFuture<Double> future = new CompletableFuture<Double>();
            future.completeExceptionally(e);
            return future;
        }
    }
}
