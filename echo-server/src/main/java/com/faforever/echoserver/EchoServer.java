package com.faforever.echoserver;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class EchoServer {

    public static final int ECHO_PORT = 14010; // TODO: env variable
    public static final int CONNECTION_TIMEOUT = 5000;

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private static ServerSocket serverSocket;
    private static volatile boolean running = true;


    public static void main(String args[]) {
        log.info("Starting echo server");

        startServer();
    }

    private static void startServer() {
        try {
            serverSocket = new ServerSocket(ECHO_PORT, 100);
        } catch (IOException e) {
            log.error("Couldn't bind server socket to port {}", ECHO_PORT);
            System.exit(1);
        }

        new Thread(EchoServer::serverSocketListener).start();
    }

    private static void serverSocketListener() {
        while(running) {
            try {
                Socket client = serverSocket.accept();

                Thread thread = new Thread(() -> clientListener(client));
                thread.start();

                executor.schedule(() -> {
                    if(! client.isClosed()) {
                        thread.stop();
                        try {
                            client.close();
                        } catch(IOException e) {
                            log.warn("Error while timing out client connection");
                        }
                    }
                }, CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);

            } catch(IOException e) {
                log.warn("Error while accepting client connection", e);
            }
        }
    }

    private static void clientListener(Socket client) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            byte[] buffer = new byte[100];
            int bytesRead = 0;
            while(bytesRead < 100) {
                bytesRead += in.read(buffer, bytesRead, 100 - bytesRead);
            }

            out.write(new byte[] {1, 2, 3, 4});

            out.close(); // flushes
            in.close();

        } catch(IOException e) {
            log.warn("Error while communicating with client {}", client.getInetAddress().getHostAddress());
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                log.warn("Error while closing client connection");
            }
        }
    }
}
