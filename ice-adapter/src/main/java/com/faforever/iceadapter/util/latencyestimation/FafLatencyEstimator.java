package com.faforever.iceadapter.util.latencyestimation;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class FafLatencyEstimator {

    private static final int FAF_LATENCY_ESTIMATION_RETRIES = 3;
    private static final int FAF_LATENCY_ESTIMATION_RETRY_PERIOD = 500;
    public static final String FAF_REQUEST = "{\"command\":\"ping\"}\n";

    private static final double UNREACHABLE_LATENCY = 10000.0;


    // estimate latency multiple times and take lowest to compensate for server processing time
    public static CompletableFuture<Double> getLatency(String hostname) {
        log.info("Estimating latency to {} using faf lobby server ping", hostname);

        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Double>> estimations = new ArrayList<>();
            for(int i = 0;i < FAF_LATENCY_ESTIMATION_RETRIES;i++) {
                int finalI = i;
                estimations.add(pingFafServer(hostname, finalI * FAF_LATENCY_ESTIMATION_RETRY_PERIOD));
            }

            CompletableFuture<Double> all = CompletableFuture.allOf(estimations.toArray(new CompletableFuture[0])).thenApply((ignored) -> {
                double lowest = estimations.stream().mapToDouble(f1 -> {
                    try {
                        return f1.get();
                    } catch (ExecutionException | InterruptedException | CancellationException e) {
                        log.error("Error while obtaining latency for faf server {}", hostname, e);
                        return UNREACHABLE_LATENCY;
                    }
                }).min().orElse(UNREACHABLE_LATENCY);
                return lowest;
            });

            try {
                return all.get(3000, TimeUnit.MILLISECONDS);
            } catch(ExecutionException | InterruptedException | CancellationException | TimeoutException e) {
                log.warn("Error or timeout while obtaining latency for faf server {}", hostname, e);
                return UNREACHABLE_LATENCY;
            }
        });
    }

    // connect to the faf server and send a ping command, waiting for the pong response
    public static CompletableFuture<Double> pingFafServer(String hostname, int delay) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(delay); } catch(InterruptedException ignored) {}

            try (Socket socket = new Socket()) {
                socket.setSoTimeout(1000);
                socket.connect(new InetSocketAddress(InetAddress.getByName(hostname), 8002), 1000);

                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                long startTime = System.currentTimeMillis();
                out.write(FAF_REQUEST.getBytes(StandardCharsets.UTF_8));
                out.flush();

                // returns '{"command":"pong"}'
                byte[] buffer = new byte[65536];
                int bytesReceived = 0;
                while(bytesReceived < 4) {
                    bytesReceived += in.read(buffer, bytesReceived, buffer.length - bytesReceived);
                }

                long latency = System.currentTimeMillis() - startTime;

                in.close();
                out.close();
                socket.close();

                return (double) latency;
            } catch(IOException | RuntimeException e) {
                log.error("Error while estimating latency to {} using faf lobby server ping", hostname, e);
                throw new CompletionException(e);
            }
        });
    }
}
