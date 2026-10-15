package com.faforever.iceadapter.dto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public record FullRelayMessage(int fromId, int targetId, byte[] data) {

    public static FullRelayMessage fromBytes(byte[] bytes, int offset, int length) {
        if (bytes == null || length < 8) {
            log.error("Minimal length for relay message is 8. Args: {}, {}, {}", bytes, offset, length);
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN);

        int fromId = buffer.getInt();
        int targetId = buffer.getInt();
        int dataLength = buffer.getInt();

        if (dataLength < 0 || dataLength > buffer.remaining()) {
            log.error("Invalid data length: {}. Args: {}, {}, {}", dataLength, bytes, offset, length);
            return null;
        }

        byte[] messageData = new byte[dataLength];
        buffer.get(messageData);

        return new FullRelayMessage(fromId, targetId, messageData);
    }

    public byte[] toBytes(byte first) {
        int dataLength = data == null ? 0 : data.length;
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 4 + 4 + dataLength).order(ByteOrder.BIG_ENDIAN);

        buffer.put(first);
        buffer.putInt(fromId);
        buffer.putInt(targetId);
        buffer.putInt(dataLength);
        if (data != null) {
            buffer.put(data);
        }
        return buffer.array();
    }

    @Override
    public String toString() {
        return "FullRelayMessage{" + "fromId=" + fromId + "targetId=" + targetId + '}';
    }
}
