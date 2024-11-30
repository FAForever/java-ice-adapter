package com.faforever.iceadapter.gpgnet;

import com.google.common.io.LittleEndianDataOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
    private final Lock writer = new ReentrantLock();

    public FaDataOutputStream(OutputStream outputStream) {
        this.outputStream = new LittleEndianDataOutputStream(new BufferedOutputStream(outputStream));
    }

    @Override
    public void write(int b) throws IOException {
        writer.lock();
        try {
            outputStream.write(b);
        } finally {
            writer.unlock();
        }
    }

    public void writeMessage(String header, Object... args) throws IOException {
        writer.lock();
        try {
            writeString(header);
            writeArgs(Arrays.asList(args));
            outputStream.flush();
        } finally {
            writer.unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        writer.lock();
        try {
            outputStream.flush();
        } finally {
            writer.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        writer.lock();
        try {
            outputStream.close();
        } finally {
            writer.unlock();
        }
    }

    private void writeArgs(List<Object> args) throws IOException {
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

    private void writeInt(int value) throws IOException {
        outputStream.writeInt(value);
    }

    private void writeByte(int b) throws IOException {
        outputStream.writeByte(b);
    }

    private void writeString(String string) throws IOException {
        outputStream.writeInt(string.length());
        outputStream.write(string.getBytes(charset));
    }
}
