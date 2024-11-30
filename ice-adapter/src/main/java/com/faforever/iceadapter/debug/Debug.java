package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class Debug {
    // TODO
    public static boolean ENABLE_DEBUG_WINDOW_LOG_TEXT_AREA =
            false; // disabled as this causes high memory and cpu load, should be replaced by limiting the number of
    // lines in the text area

    public static boolean ENABLE_DEBUG_WINDOW = false;
    public static boolean ENABLE_INFO_WINDOW = false;
    public static int DELAY_UI_MS = 0; // delays the launch of the user interface by X ms

    public static int RPC_PORT;

    private static final DebugFacade debugFacade = new DebugFacade();

    public static void register(Debugger debugger) {
        debugFacade.add(debugger);
    }

    public static void remove(Debugger debugger) {
        debugFacade.remove(debugger);
    }

    public static void init() {
        new TelemetryDebugger(IceAdapter.getTelemetryServer(), IceAdapter.getGameId(), IceAdapter.getId(), IceAdapter.getExecutor());

        // Debugger window is started and set to debugFuture when either window is requested as the info window can be
        // used to open the debug window
        // This is not used anymore as the debug window is started and hidden in case it is requested via the tray icon
        if (!ENABLE_DEBUG_WINDOW && !ENABLE_INFO_WINDOW) {
            return;
        }

        if (isJavaFxSupported()) {
            CompletableFuture.runAsync(() -> {
                try {
                    Class.forName("com.faforever.iceadapter.debug.DebugWindow")
                            .getMethod("launchApplication")
                            .invoke(null);
                } catch (IllegalAccessException
                        | ClassNotFoundException
                        | NoSuchMethodException
                        | InvocationTargetException e) {
                    log.error("Could not create DebugWindow. Running without debug window.", e);
                }
            }, IceAdapter.getExecutor());
        } else {
            log.info("No JavaFX support detected. Running without debug window.");
        }
    }

    public static Debugger debug() {
        return debugFacade;
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
