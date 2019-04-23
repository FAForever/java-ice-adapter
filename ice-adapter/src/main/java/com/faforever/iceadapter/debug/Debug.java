package com.faforever.iceadapter.debug;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
public class Debug {
	//TODO
	public static boolean ENABLE_DEBUG_WINDOW_LOG_TEXT_AREA = false; // disabled as this causes high memory and cpu load, should be replaced by limiting the number of lines in the text area

	public static boolean ENABLE_DEBUG_WINDOW = false;
	public static boolean ENABLE_INFO_WINDOW = false;
	public static int DELAY_UI_MS = 0;//delays the launch of the user interface by X ms

	static CompletableFuture<Debugger> debug = new CompletableFuture<>();

	public static void init() {
		// Debugger window is started and set to debugFuture when either window is requested as the info window can be used to open the debug window
		if(! ENABLE_DEBUG_WINDOW && ! ENABLE_INFO_WINDOW) {
			debug.complete(new NoDebugger());
			return;
		}

		if(isJavaFxSupported()) {
			new Thread(() -> {
				try {
					Class.forName("com.faforever.iceadapter.debug.DebugWindow").getMethod("launchApplication").invoke(null);
				} catch (IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
					e.printStackTrace();
					log.error("Could not create DebugWindow. Running without debug window.");
					debug.complete(new NoDebugger());
				}
			}).start();//Completes future once application started
//			debug.join();
		} else {
			log.info("No JavaFX support detected. Running without debug window.");
			debug.complete(new NoDebugger());
		}
	}


	public static Debugger debug() {
		try {
			return debug.get();
		} catch (InterruptedException | ExecutionException e) {
			log.error("Could not get debugger", e);
			return new NoDebugger();
		}
	}

	public static boolean isJavaFxSupported() {
		try {
			Debug.class.getClassLoader().loadClass("javafx.application.Application");
			return true;
		} catch(ClassNotFoundException e) {
			log.warn("Could not create debug window, no JavaFX found.");
			return false;
		}
	}
}
