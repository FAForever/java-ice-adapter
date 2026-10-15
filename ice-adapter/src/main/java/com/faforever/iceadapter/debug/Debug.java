package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ui.IceWindow;
import com.faforever.iceadapter.ui.InfoWindow;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Debug {
    public static boolean ENABLE_DEBUG_WINDOW = false;
    public static boolean ENABLE_INFO_WINDOW = false;
    public static int DELAY_UI_MS = 0; // delays the launch of the user interface by X ms

    private static final DebugFacade debugFacade = new DebugFacade();

    public static void register(Debugger debugger) {
        debugFacade.add(debugger);
    }

    public static void remove(Debugger debugger) {
        debugFacade.remove(debugger);
    }

    public static void init() {
        if (isJavaFxSupported()) {
            new Thread(IceWindow::launch).start();

            if (Debug.ENABLE_INFO_WINDOW) {
                CompletableFuture.runAsync(
                        () -> runOnUIThread(InfoWindow::launch),
                        CompletableFuture.delayedExecutor(Debug.DELAY_UI_MS, TimeUnit.MILLISECONDS));
            }

        } else {
            log.info("No JavaFX support detected. Running without debug window.");
        }
    }

    public static void close() {
        debugFacade.close();
    }

    public static Debugger debug() {
        return debugFacade;
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static boolean isJavaFxSupported() {
        try {
            Debug.class.getClassLoader().loadClass("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException e) {
            log.warn("Could not create debug window, no JavaFX found.");
            return false;
        }
    }
}
