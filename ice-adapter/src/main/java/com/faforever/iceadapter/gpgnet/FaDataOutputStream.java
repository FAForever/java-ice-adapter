package com.faforever.iceadapter.gpgnet;

import com.google.common.io.LittleEndianDataOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Writes data to Forged Alliance (the forgedalliance, not the lobby).
 */
public class FaDataOutputStream extends OutputStream {

    public static final int FIELD_TYPE_INT = 0;
    public static final int FIELD_TYPE_FOLLOWING_STRING = 2;
    public static final int FIELD_TYPE_STRING = 1;
    public static final char DELIMITER = '\b';
    private final LittleEndianDataOutputStream outputStream;
    private final Charset charset = StandardCharsets.UTF_8;

    public FaDataOutputStream(OutputStream outputStream) {
        this.outputStream = new LittleEndianDataOutputStream(new BufferedOutputStream(outputStream));
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }

    public synchronized void writeArgs(List<Object> args) throws IOException {
        writeInt(args.size());

        for (Object arg : args) {
            if (arg instanceof Double d) {
                writeByte(FIELD_TYPE_INT);
                writeInt(d.intValue());
            } else if (arg instanceof Integer i) {
                writeByte(FIELD_TYPE_INT);
                writeInt(i);
            } else if (arg instanceof String value) {
                writeByte(FIELD_TYPE_STRING);
                writeString(value);
            }
        }
    }

    public void writeInt(int value) throws IOException {
        outputStream.writeInt(value);
    }

    public synchronized void writeByte(int b) throws IOException {
        outputStream.writeByte(b);
    }

    public synchronized void writeString(String string) throws IOException {
        outputStream.writeInt(string.length());
        outputStream.write(string.getBytes(charset));
    }

    public synchronized void writeMessage(String header, Object... args) throws IOException {
        writeString(header);
        writeArgs(Arrays.asList(args));
        outputStream.flush();
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }
}
