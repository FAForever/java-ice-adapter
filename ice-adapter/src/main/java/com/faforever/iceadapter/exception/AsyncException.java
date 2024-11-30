package com.faforever.iceadapter.exception;

/**
 * Errors caused during execution in the asynchronous service
 */
public class AsyncException extends RuntimeException {

    public AsyncException(Throwable cause) {
        super(cause);
    }
}
