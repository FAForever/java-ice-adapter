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
import lombok.extern.slf4j.Slf4j;

/**
 * Writes data to Forged Alliance (the forgedalliance, not the lobby).
 */
@Slf4j
public class FaDataOutputStream extends OutputStream {

    public static final int FIELD_TYPE_INT = 0;
    public static final int FIELD_TYPE_STRING = 1;
    public static final int FIELD_TYPE_FOLLOWING_STRING = 2;
    public static final char DELIMITER = '\b';

    private final LittleEndianDataOutputStream outputStream;
    private final Charset charset = StandardCharsets.UTF_8;
    private final Lock writer = new ReentrantLock();
    private volatile boolean closed = false;

    public FaDataOutputStream(OutputStream outputStream) {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream cannot be null");
        }
        this.outputStream = new LittleEndianDataOutputStream(new BufferedOutputStream(outputStream));
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) throw new IOException("Stream is closed");
        writer.lock();
        try {
            outputStream.write(b);
        } finally {
            writer.unlock();
        }
    }

    /**
     * Writes a message with header and arguments.
     * Supports Integer, Double (as int), and String.
     *
     * @param header the message header (must not be null)
     * @param args   the arguments (null values are skipped)
     * @throws IOException if an I/O error occurs
     */
    public void writeMessage(String header, Object... args) throws IOException {
        if (header == null) {
            throw new IllegalArgumentException("Header cannot be null");
        }

        writer.lock();
        try {
            if (closed) throw new IOException("Stream is closed");

            writeString(header);
            writeArgs(Arrays.asList(args));
            outputStream.flush();
        } finally {
            writer.unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        if (closed) {
            return;
        }
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
            if (closed) return;
            closed = true;
            outputStream.close();
        } finally {
            writer.unlock();
        }
    }

    private void writeArgs(List<Object> args) throws IOException {
        if (args == null) {
            writeInt(0);
            return;
        }

        List<Object> validArgs = args.stream()
                .filter(arg -> {
                    if (arg == null) {
                        return false;
                    }
                    return true;
                })
                .toList();

        writeInt(validArgs.size());

        for (Object arg : validArgs) {
            if (arg instanceof Double d) {
                writeByte(FIELD_TYPE_INT);
                writeInt(d.intValue());
            } else if (arg instanceof Integer i) {
                writeByte(FIELD_TYPE_INT);
                writeInt(i);
            } else if (arg instanceof String str) {
                writeByte(FIELD_TYPE_STRING);
                writeString(str);
            } else {
                log.error("Unsupported argument type: {} {}", arg.getClass().getSimpleName(), arg);
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
        if (string == null) {
            string = "";
        }
        byte[] bytes = string.getBytes(charset);
        outputStream.writeInt(bytes.length);
        outputStream.write(bytes);
    }
}
