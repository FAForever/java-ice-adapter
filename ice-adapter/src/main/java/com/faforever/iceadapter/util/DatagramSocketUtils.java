package com.faforever.iceadapter.util;

import java.net.DatagramSocket;
import java.net.SocketException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class DatagramSocketUtils {
    public static final int MAX_SIZE_PACKET = /* assumed MTU */ 1500 - /* IPv4 header */ 20 - /* UDP header */ 8;

    public void resizeBuffer(DatagramSocket socket) {
        try {
            socket.setReceiveBufferSize(MAX_SIZE_PACKET);
            socket.setSendBufferSize(MAX_SIZE_PACKET);
        } catch (SocketException e) {
            if (!socket.isClosed()) {
                log.error("Failed to resize socket buffer on {}", MAX_SIZE_PACKET, e);
            }
        }
    }

    public boolean isStunPacket(byte[] data, int length) {
        if (length < 2) {
            return false;
        }
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return (type == 0x0000)
                || // Maybe keep-alive/misfire
                (type == 0x0001)
                || // Binding Request
                (type == 0x0101)
                || // Binding Response
                (type == 0x0115)
                || // Shared Secret Request
                (type == 0x0116)
                || // Shared Secret Response
                (type == 0x0002); // Binding Indication
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
