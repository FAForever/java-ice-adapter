package com.faforever.iceadapter.ice;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * In-memory analog for UDP datagram sockets used in tests.
 * <p>
 * This class simulates the networking behavior of <em>Supreme Commander: Forged Alliance</em> by providing
 * an in-memory implementation of {@link DatagramSocket} that routes packets without actual network I/O.
 * It is primarily used for integration tests that emulate P2P communication between game clients.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InMemoryDatagramSocket extends DatagramSocket {

    private final BlockingQueue<InMemoryPacket> receivedPackets = new LinkedBlockingQueue<>();
    private final LongAdder sendCounter = new LongAdder();
    private final LongAdder receiveCounter = new LongAdder();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Thread readerThread;

    public InMemoryDatagramSocket() throws SocketException {
        super(0);
        this.running.set(true);
        this.readerThread = new Thread(this::readerLoop, "InMemoryDatagramSocket-reader");
        this.readerThread.setDaemon(true);
    }

    public void start() {
        this.readerThread.start();
    }

    private void readerLoop() {
        while (running.get() && !isClosed()) {
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                super.receive(packet);
            } catch (IOException e) {
                if (isClosed()) {
                    break;
                }
                // Give a small delay to avoid busy-waiting on errors
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (!running.get() || isClosed()) {
                break;
            }
            // Copy data to avoid holding onto the buffer
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
            receivedPackets.offer(new InMemoryPacket(data, packet.getAddress(), packet.getPort()));
            receiveCounter.increment();
        }
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (isClosed()) {
            throw new IOException("socket is closed");
        }
        super.send(p);
        sendCounter.increment();
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            receivedPackets.clear();
            readerThread.interrupt();
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.close();
    }

    /**
     * Returns all received byte payloads in order.
     */
    public List<byte[]> getReceivedBytes() {
        return receivedPackets.stream().map(pkt -> pkt.data).toList();
    }

    /**
     * Sends a string as a UDP datagram encoded with UTF-8.
     * <p>
     * The string is sent to the socket's connected address and port.
     * Throws {@code IOException} if the socket is not connected or is closed.
     *
     * @param text the string to send
     * @throws IOException if the socket is closed or not connected
     */
    public void sendString(String text) throws IOException {
        sendString(text, StandardCharsets.UTF_8);
    }

    /**
     * Sends a string as a UDP datagram encoded with the specified charset.
     * <p>
     * The string is sent to the socket's connected address and port.
     * Throws {@code IOException} if the socket is not connected or is closed.
     *
     * @param text    the string to send
     * @param charset the charset to encode the string with
     * @throws IOException if the socket is closed or not connected
     */
    public void sendString(String text, Charset charset) throws IOException {
        Objects.requireNonNull(text, "Text must not be null");
        byte[] bytes = text.getBytes(charset);
        sendBytes(bytes);
    }

    /**
     * Sends a raw byte array as a UDP datagram.
     *
     * @param bytes the bytes to send
     * @throws IOException if the socket is closed or not connected
     */
    public void sendBytes(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "Bytes must not be null");
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        send(packet);
    }

    /**
     * Clears all captured packets.
     */
    public void clear() {
        receivedPackets.clear();
    }

    /**
     * Represents a received packet with its source address info.
     */
    @Data
    static class InMemoryPacket {
        final byte[] data;
        final InetAddress address;
        final int port;

        InMemoryPacket(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.address = address;
            this.port = port;
        }
    }
}
