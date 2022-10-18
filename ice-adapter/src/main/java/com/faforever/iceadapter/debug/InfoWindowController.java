package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.util.TrayIcon;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import lombok.SneakyThrows;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;

public class InfoWindowController {
    public Button killAdapterButton;
    public Button showDebugWindowButton;
    public Button showTelemetryWebUiButton;
    public Button minimizeToTray;


    private volatile int killRequests = 0;

    public void onKillAdapterClicked(ActionEvent actionEvent) {
        killRequests++;

        if (killRequests < 3) {
            killAdapterButton.setText("This will disconnect you from the game! Click " + (3 - killRequests) + " more times.");
        } else {
            System.exit(337);
        }
    }

    public void onShowDebugWindowClicked(ActionEvent actionEvent) {
        DebugWindow.INSTANCE.thenAcceptAsync(DebugWindow::showWindow);
    }

    @SneakyThrows
    public void onTelemetryWebUiClicked(ActionEvent actionEvent) {
        String url = "%s/app.html?gameId=%d&playerId=%d".formatted(
                IceAdapter.TELEMETRY_SERVER.replaceFirst("ws", "http"),
                IceAdapter.gameId,
                IceAdapter.id
        );

        new Thread(() -> {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
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
        showTelemetryWebUiButton.setOnAction(this::onTelemetryWebUiClicked);
        minimizeToTray.setOnAction(this::onMinimizeToTrayClicked);

        if (!TrayIcon.isTrayIconSupported()) {
            minimizeToTray.setVisible(false);
        }
    }
}
