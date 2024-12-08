package com.faforever.iceadapter.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ExecutorHolder {
    public ExecutorService getExecutor() {
        int numberOfCores = Runtime.getRuntime().availableProcessors();
        return Executors.newFixedThreadPool(numberOfCores);
    }
}
