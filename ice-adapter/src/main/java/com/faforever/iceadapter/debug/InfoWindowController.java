package com.faforever.iceadapter.debug;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class InfoWindowController {
	public Button killAdapterButton;
	public Button showDebugWindowButton;

	public void onKillAdapterClicked(ActionEvent actionEvent) {
		System.exit(337);
	}

	public void onShowDebugWindowClicked(ActionEvent actionEvent) {
		Debug.debug.thenAcceptAsync(debugger -> {
			if(debugger instanceof DebugWindow) {
				((DebugWindow)debugger).showWindow();
			}
		});
	}

	@FXML
	private void initialize() {
		killAdapterButton.setOnAction(this::onKillAdapterClicked);
		showDebugWindowButton.setOnAction(this::onShowDebugWindowClicked);
	}
}
