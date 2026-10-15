package util;

import logging.Logger;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.SocketException;

public class Util {

	public static int getAvaiableTCPPort() {
		try {
			ServerSocket socket = new ServerSocket(0);
			int port = socket.getLocalPort();
			socket.close();
			return port;
		} catch (IOException e) {
			Logger.error("Could not find available TCP port.");
			Logger.crash(e);
			return -1;
		}
	}

	public static int getAvaiableUDPPort() {
		try {
			DatagramSocket socket = new DatagramSocket(0);
			int port = socket.getLocalPort();
			socket.close();
			return port;
		} catch (SocketException e) {
			Logger.error("Could not find available TCP port.");
			Logger.crash(e);
			return -1;
		}
	}

	public static void assertThat(boolean b) {
		if(! b) {
			Throwable t = new Throwable();
			Logger.error("Assertion failed!!! %s->%s:%d", t.getStackTrace()[1].getClassName(), t.getStackTrace()[1].getMethodName(), t.getStackTrace()[1].getLineNumber());
		}
	}
}
