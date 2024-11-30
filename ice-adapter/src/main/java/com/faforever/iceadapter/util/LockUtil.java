package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.locks.Lock;

@UtilityClass
public class LockUtil {
    public void executeWithLock(Lock lock, Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
