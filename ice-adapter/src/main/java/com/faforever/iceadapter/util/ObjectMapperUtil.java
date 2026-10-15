package com.faforever.iceadapter.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.github.luben.zstd.Zstd;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class ObjectMapperUtil {

    private final ObjectMapper mapperJson = new ObjectMapper().registerModule(new BlackbirdModule());

    public <T> T fromBytes(byte[] data, Class<T> clazz) {
        String json = new String(data, StandardCharsets.UTF_8);
        return fromJson(json, clazz);
    }

    public String toJson(Object object) {
        try {
            return mapperJson.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Cant serialize object to bytes {}", object, e);
            return "";
        }
    }

    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return mapperJson.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Cant deserialize string {} to object {}", json, clazz, e);
            return null;
        }
    }

    public byte[] toBytes(Object object) {
        return toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toBytesAndAddFirstByte(byte first, Object object, boolean compress) {
        byte[] bytes = toBytes(object);
        if (compress) {
            bytes = Zstd.compress(bytes);
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + bytes.length);
        buffer.put(first);
        buffer.put(bytes);
        return buffer.array();
    }

    public <T> T fromBytesAndWithOutFirstByte(byte[] bytes, Class<T> clazz, boolean decompress) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(1);
        byte[] objBytes = new byte[buffer.remaining()];
        buffer.get(objBytes);

        if (decompress) {
            objBytes = Zstd.decompress(objBytes);
        }
        return fromBytes(objBytes, clazz);
    }
}
