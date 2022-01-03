package com.faforever.iceadapter.util.latencyestimation;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public class HttpLatencyEstimator {

//    public static final String HTTP_REQUEST = "GET / HTTP/1.1\n" +
//            "Host: traefik.%s\n" +
//            "\n";
    public static final String HTTP_REQUEST = "GET / HTTP/0.0\n\n";

    public static CompletableFuture<Double> getLatencyManualHttp(String hostname) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket()) {
                log.info("Estimating latency to {} using http", hostname);

                socket.connect(new InetSocketAddress(InetAddress.getByName(hostname), 80));
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                long startTime = System.currentTimeMillis();
                out.write(String.format(HTTP_REQUEST, hostname).getBytes(StandardCharsets.UTF_8));
                out.flush();

                byte[] buffer = new byte[65536];
                int bytesReceived = 0;
                while(bytesReceived < 4) {
                    bytesReceived += in.read(buffer, bytesReceived, buffer.length - bytesReceived);
                } // TODO timeout

                System.out.println("Received " + new String(Arrays.copyOfRange(buffer, 0, bytesReceived)));

                long latency = System.currentTimeMillis() - startTime;

                in.close();
                out.close();
                socket.close();

                return (double) latency;
            } catch(IOException | RuntimeException e) {
                log.error("Error while estimating latency to {} using http", hostname);
                throw new CompletionException(e);
            }
        });
    }



    public static CompletableFuture<Double> getLatencyHttp(String hostname) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Estimating latency to {} using http", hostname);


                HttpURLConnection connection = (HttpURLConnection) (new URL(String.format("https://traefik.%s", hostname))).openConnection();
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);

                long startTime = System.currentTimeMillis();
                connection.connect();

                if(connection.getResponseCode() != 401) {
                    log.error("Received response code {} while attempting to estimate latency to {}", connection.getResponseCode(), hostname);
                    throw new RuntimeException("Invalid http response code " + connection.getResponseCode());
                }

                long latency = System.currentTimeMillis() - startTime;

                return (double) latency;
            } catch(IOException | RuntimeException e) {
                log.error("Error while estimating latency to {} using http", hostname);
                throw new CompletionException(e);
            }
        });
    }
}
