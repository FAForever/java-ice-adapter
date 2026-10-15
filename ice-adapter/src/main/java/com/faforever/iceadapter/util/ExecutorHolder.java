package com.faforever.iceadapter.util;

import com.faforever.iceadapter.services.impl.IceAsyncImpl;
import java.util.concurrent.*;
import lombok.experimental.UtilityClass;

/**
 * Used in {@link IceAsyncImpl}. We have to use Executors.defaultThreadFactory() since Ice4J is not allowed to use virtual threads in Java 21.
 */
@UtilityClass
public class ExecutorHolder {

    private final ExecutorService executorService = new ThreadPoolExecutor(
            getCoreForExecutor(10),
            getCoreForExecutor(20),
            0L,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(1000, Task.createComparator()),
            Executors.defaultThreadFactory());
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);

    public ExecutorService getExecutor() {
        return executorService;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutorService;
    }

    private static int getCoreForExecutor(int size) {
        return Math.max(size, Runtime.getRuntime().availableProcessors());
    }
}
