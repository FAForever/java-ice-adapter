package com.faforever.iceadapter.util.latencyestimation;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class EchoLatencyEstimator {

    private static final int LATENCY_ESTIMATION_RETRIES = 3;
    private static final int LATENCY_ESTIMATION_RETRY_PERIOD = 500;

    private static final double UNREACHABLE_LATENCY = 10000.0;


    // estimate latency multiple times and take lowest to compensate for server processing time
    public static CompletableFuture<Double> getLatency(String hostname) {
        log.info("Estimating latency to {} using echo server ping", hostname);

        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Double>> estimations = new ArrayList<>();
            for(int i = 0;i < LATENCY_ESTIMATION_RETRIES;i++) {
                int finalI = i;
                estimations.add(pingEchoServer(hostname, finalI * LATENCY_ESTIMATION_RETRY_PERIOD));
            }

            CompletableFuture<Double> all = CompletableFuture.allOf(estimations.toArray(new CompletableFuture[0])).thenApply((ignored) -> {
                double lowest = estimations.stream().mapToDouble(f1 -> {
                    try {
                        return f1.get();
                    } catch (ExecutionException | InterruptedException | CancellationException e) {
                        log.error("Error while obtaining latency for echo server {}", hostname, e);
                        return UNREACHABLE_LATENCY;
                    }
                }).min().orElse(UNREACHABLE_LATENCY);
                return lowest;
            });

            try {
                return all.get(3000, TimeUnit.MILLISECONDS);
            } catch(ExecutionException | InterruptedException | CancellationException | TimeoutException e) {
                log.warn("Error or timeout while obtaining latency for echo server {}", hostname, e);
                return UNREACHABLE_LATENCY;
            }
        });
    }

    // connect to the faf server and send a ping command, waiting for the pong response
    public static CompletableFuture<Double> pingEchoServer(String hostname, int delay) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(delay); } catch(InterruptedException ignored) {}

            try (Socket socket = new Socket()) {
                socket.setSoTimeout(1000);
                socket.connect(new InetSocketAddress(InetAddress.getByName(hostname), 14010), 1000);

                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                byte[] data = new byte[100];
                Arrays.fill(data, (byte) 55);

                long startTime = System.currentTimeMillis();
                out.write(data, 0, 100);
                out.flush();

                // returns '{"command":"pong"}'
                byte[] buffer = new byte[10];
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
                log.error("Error while estimating latency to {} using echo server", hostname, e);
                throw new CompletionException(e);
            }
        });
    }

}
