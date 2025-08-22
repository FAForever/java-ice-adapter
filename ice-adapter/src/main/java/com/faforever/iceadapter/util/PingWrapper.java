package com.faforever.iceadapter.util;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.faforever.iceadapter.IceAdapter;
import lombok.extern.slf4j.Slf4j;

/*
 * A wrapper around getting the response time using InetAddress.
 */
@Slf4j
public class PingWrapper {
    private static final Integer INET_TIMEOUT = 5000;

    /*
     * Checks the availability of the specified address (host) and returns the time it took to receive a response.
     */
    public static CompletableFuture<Double> getLatency(String address) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        long start = System.currentTimeMillis();
                        InetAddress inet = InetAddress.getByName(address);
                        if (inet.isReachable(INET_TIMEOUT)) {
                            long finish = System.currentTimeMillis();
                            return (double) (finish - start);
                        }
                        throw new RuntimeException();
                    } catch (IOException | RuntimeException e) {
                        throw new CompletionException(e);
                    }
                }, IceAdapter.getExecutor());
    }
}
