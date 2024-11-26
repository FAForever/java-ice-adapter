package com.faforever.iceadapter.debug;

import static javafx.application.Application.STYLESHEET_MODENA;
import static javafx.application.Application.setUserAgentStylesheet;

import java.io.IOException;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InfoWindow {

    public static InfoWindow INSTANCE;

    private Stage stage;
    private Parent root;
    private Scene scene;
    private InfoWindowController controller;

    private static final int WIDTH = 533;
    private static final int HEIGHT = 330;

    public InfoWindow() {
        INSTANCE = this;
    }

    public void init() {
        stage = new Stage();
        stage.getIcons().add(new Image("https://faforever.com/images/faf-logo.png"));

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
        stage.setOnCloseRequest(Event::consume);
        stage.show();

        log.info("Created info window.");
    }

    public void minimize() {
        Platform.setImplicitExit(false);
        Platform.runLater(this.stage::hide);
    }

    public void show() {
        Platform.runLater(() -> {
            this.stage.show();
            Platform.setImplicitExit(true);
        });
    }
}
