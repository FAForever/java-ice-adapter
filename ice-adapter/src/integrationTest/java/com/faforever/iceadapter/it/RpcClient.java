package com.faforever.iceadapter.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The client side of the adapter's JSON-RPC interface: what the FAF client does, minus the lobby.
 *
 * <p>Every notification the adapter sends is kept, so a test can await one that arrived before it
 * started waiting.
 */
final class RpcClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final Socket socket;
    private final OutputStream out;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final List<Consumer<Notification>> listeners = new CopyOnWriteArrayList<>();

    record Notification(String method, JsonNode params) {}

    private RpcClient(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        Thread.ofPlatform().daemon().name("rpc-reader-" + socket.getPort()).start(this::readLoop);
    }

    /** Connects to the adapter's RPC port, retrying while the adapter is still starting up. */
    static RpcClient connect(int port, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return new RpcClient(socket);
            } catch (IOException e) {
                socket.close();
                if (System.nanoTime() > deadline) {
                    throw new IOException("Adapter JSON-RPC port " + port + " not reachable within " + timeout, e);
                }
                Thread.sleep(200);
            }
        }
    }

    JsonNode call(String method, Object... params) throws Exception {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", MAPPER.valueToTree(params));
        synchronized (out) {
            out.write((MAPPER.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        try {
            JsonNode reply = response.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (reply.hasNonNull("error")) {
                throw new IllegalStateException(method + " failed: " + reply.get("error"));
            }
            return reply.get("result");
        } catch (TimeoutException e) {
            throw new TimeoutException(method + " got no response within " + CALL_TIMEOUT);
        } finally {
            pending.remove(id);
        }
    }

    /** Calls {@code status}, which the adapter returns as a JSON document inside a string. */
    JsonNode status() throws Exception {
        return MAPPER.readTree(call("status").asText());
    }

    /** Registers a listener for notifications arriving from now on. */
    void onNotification(Consumer<Notification> listener) {
        listeners.add(listener);
    }

    /** Waits for a notification, including one that already arrived. */
    Notification await(String description, Predicate<Notification> condition, Duration timeout)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (notifications) {
            while (true) {
                for (Notification notification : notifications) {
                    if (condition.test(notification)) {
                        return notification;
                    }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("No " + description + " within " + timeout + "; received "
                            + notifications.stream().map(Notification::method).toList());
                }
                TimeUnit.NANOSECONDS.timedWait(notifications, remaining);
            }
        }
    }

    Notification awaitGpgNetMessage(String header, String firstChunk, Duration timeout)
            throws InterruptedException, TimeoutException {
        return await(
                "onGpgNetMessageReceived " + header + " " + firstChunk,
                n -> n.method().equals("onGpgNetMessageReceived")
                        && n.params().get(0).asText().equals(header)
                        && n.params().get(1).path(0).asText().equals(firstChunk),
                timeout);
    }

    private void readLoop() {
        try (MappingIterator<JsonNode> messages =
                MAPPER.readerFor(JsonNode.class).readValues(socket.getInputStream())) {
            while (messages.hasNextValue()) {
                JsonNode message = messages.nextValue();
                if (message.has("method")) {
                    Notification notification =
                            new Notification(message.get("method").asText(), message.get("params"));
                    synchronized (notifications) {
                        notifications.add(notification);
                        notifications.notifyAll();
                    }
                    listeners.forEach(listener -> listener.accept(notification));
                } else if (message.has("id")) {
                    CompletableFuture<JsonNode> response =
                            pending.get(message.get("id").asLong());
                    if (response != null) {
                        response.complete(message);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // The adapter went away; pending calls time out and name the method.
        }
    }

    /** Closes with a TCP reset rather than a FIN, as when the client's connection breaks. */
    void abort() throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
