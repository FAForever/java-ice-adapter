package client;

import client.experimental.VoiceChat;
import client.forgedalliance.ForgedAlliance;
import client.ice.ICEAdapter;
import data.IceStatus;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import logging.Logger;
import net.ClientInformationMessage;
import net.ScenarioOptionsMessage;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;

import static com.github.nocatch.NoCatch.noCatch;

public class TestClient {

	public static boolean DEBUG_MODE = false;
	private static final int INFORMATION_INTERVAL = 2500;

	public static String username;
	public static int playerID;

	public static ForgedAlliance forgedAlliance;
	public static BooleanProperty isGameRunning = new SimpleBooleanProperty(false);

	public static ScenarioOptionsMessage scenarioOptions = new ScenarioOptionsMessage();

	public static void joinGame(int gpgpnetPort, int lobbyPort) {
		Logger.info("Starting ForgedAlliance.");
		forgedAlliance = new ForgedAlliance(gpgpnetPort, lobbyPort);
		isGameRunning.set(true);
	}

	public static void leaveGame() {
		Logger.info("Stopping ForgedAlliance.");
		forgedAlliance.stop();
		forgedAlliance = null;
		isGameRunning.set(false);
	}


	private static void informationThread() {
		while(true) {

			if(Logger.collectedLog.length() > 30000) {
				Logger.collectedLog = Logger.collectedLog.substring(0, 30000);
				Logger.error("Log too long. Cannot send to server");
			}

			ClientInformationMessage message;
			synchronized (TestServerAccessor.latencies) {
				IceStatus iceStatus = ICEAdapter.status();
				message = new ClientInformationMessage(username, playerID, System.currentTimeMillis(), TestServerAccessor.latencies, scenarioOptions.isUploadIceStatus() ? iceStatus : new IceStatus(), scenarioOptions.isUploadLog() ? Logger.collectedLog : "", isGameRunning.get() ? forgedAlliance.getPeers() : null);
				TestServerAccessor.latencies = new LinkedList<>();
			}
			Logger.collectedLog = "";

			TestServerAccessor.send(message);

			if(forgedAlliance != null) {
				forgedAlliance.getPeers().forEach(p -> {
					synchronized (p) {
						p.clearLatencyHistory();
					}
				});
			}

//			Logger.debug("Sent: %s", new Gson().toJson(message));

			try { Thread.sleep(INFORMATION_INTERVAL); } catch(InterruptedException e) {}
		}
	}




	public static void main(String args[]) {
		boolean skipGDRP = false;
		if (args.length >= 1) {
            for (String arg : args) {
                if (arg.equals("--debug")) {
                    DEBUG_MODE = true;
                }

                if(arg.replaceAll("-","").equals("help") || arg.equals("/?")){
					System.out.println("Possible Arguments:\n" +
							"--skip             Skips the GDRP check\n" +
							"--name=yourname    Sets a custom name without asking\n" +
							"--port=newport     Changes external adapter port\n" +
							"--debug            Turns on debug mode");
                	System.exit(0);
				}

				if(arg.equals("--skip")){
					skipGDRP=true;
				}

                if(arg.startsWith("--name=")){
                	username = arg.replaceFirst("--name=","");
				}

                if(arg.startsWith("--port=")) {
                    ICEAdapter.EXTERNAL_ADAPTER_PORT = Integer.parseInt(arg.replaceFirst("--port=",""));
                }
			}
		}
		else {
			Logger.enableLogging();
		}
		Logger.init("ICE adapter testclient");

		GUI.init(args);

		if(!skipGDRP) {
			GUI.showGDPRDialog();
		}
		if(username==null) {
			getUsername();
		}

		TestServerAccessor.init();

		ICEAdapter.init();

		GUI.instance.showStage();

		VoiceChat.init();

		new Thread(TestClient::informationThread).start();
	}

	public static void getUsername() {
		String preGeneratedUsername = "iceTester" + (int)(Math.random() * 10000);

		if(! TestClient.DEBUG_MODE && new File("iceTestUsername").exists()) {
			try {
				preGeneratedUsername = FileUtils.readFileToString(new File("iceTestUsername"),"UTF-8");
			} catch (IOException e) {
				Logger.error("Error while reading old username from file.", e);
			}
		}

		GUI.showUsernameDialog(preGeneratedUsername);

		noCatch(() -> FileUtils.writeStringToFile(new File("iceTestUsername"), TestClient.username, "UTF-8"));
	}

	public static void close() {
		VoiceChat.close();

		TestServerAccessor.disconnect();
		ICEAdapter.close();

		Logger.close();


		System.exit(0);
	}
}
