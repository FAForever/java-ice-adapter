package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.util.TrayIcon;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class InfoWindowController {
	public Button killAdapterButton;
	public Button showDebugWindowButton;
	public Button minimizeToTray;


	private volatile int killRequests = 0;
	public void onKillAdapterClicked(ActionEvent actionEvent) {
		killRequests++;

		if(killRequests < 3) {
			killAdapterButton.setText("This will disconnect you from the game! Click " + (3 - killRequests) + " more times.");
		} else {
			System.exit(337);
		}
	}

	public void onShowDebugWindowClicked(ActionEvent actionEvent) {
		DebugWindow.INSTANCE.thenAcceptAsync(DebugWindow::showWindow);
	}

	public void onMinimizeToTrayClicked(ActionEvent actionEvent) {
		if (TrayIcon.isTrayIconSupported() && InfoWindow.INSTANCE != null) {
			InfoWindow.INSTANCE.minimize();
		}
	}

	@FXML
	private void initialize() {
		killAdapterButton.setOnAction(this::onKillAdapterClicked);
		showDebugWindowButton.setOnAction(this::onShowDebugWindowClicked);
		minimizeToTray.setOnAction(this::onMinimizeToTrayClicked);

		if (!TrayIcon.isTrayIconSupported()) {
			minimizeToTray.setVisible(false);
		}
	}
}
