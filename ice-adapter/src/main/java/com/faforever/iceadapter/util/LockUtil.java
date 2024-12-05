package com.faforever.iceadapter.util;

import java.util.concurrent.locks.Lock;
import lombok.experimental.UtilityClass;

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
