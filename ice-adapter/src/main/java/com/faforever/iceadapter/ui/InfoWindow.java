package com.faforever.iceadapter.ui;

import static javafx.application.Application.STYLESHEET_MODENA;
import static javafx.application.Application.setUserAgentStylesheet;

import com.faforever.iceadapter.LogoUtils;
import com.faforever.iceadapter.ui.controller.InfoWindowController;
import java.io.IOException;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InfoWindow {

    public static InfoWindow INSTANCE;

    private Stage stage;
    private Parent root;
    private Scene scene;
    private InfoWindowController controller;

    private static final int WIDTH = 540;
    private static final int HEIGHT = 370;

    public void start(Stage stage) {
        INSTANCE = this;
        this.stage = stage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/infoWindow.fxml"));
            root = loader.load();
            controller = loader.getController();
        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root, WIDTH, HEIGHT);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter");
        stage.setResizable(false);
        stage.setOnCloseRequest(event -> minimize());
        stage.show();

        log.info("Created info window.");
    }

    public void minimize() {
        Platform.setImplicitExit(false);
        runOnUIThread(this.stage::hide);
    }

    public void showWindow() {
        runOnUIThread(() -> {
            this.stage.show();
        });
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launch() {
        log.info("Launching info window.");
        if (INSTANCE == null) {
            runOnUIThread(() -> new InfoWindow().start(new Stage()));
        } else {
            INSTANCE.showWindow();
        }
    }
}
