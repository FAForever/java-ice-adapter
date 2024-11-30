package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UtilityClass
public class ExecutorHolder {
    public ExecutorService getExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
