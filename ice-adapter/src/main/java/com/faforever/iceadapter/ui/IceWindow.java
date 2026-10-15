package com.faforever.iceadapter.ui;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.LogoUtils;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.services.impl.UIAdapterImpl;
import com.faforever.iceadapter.ui.controller.WindowController;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EqualsAndHashCode(callSuper = false)
public class IceWindow extends Application {
    public static IceWindow INSTANCE;

    private Parent root;
    private Scene scene;
    private WindowController controller;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        INSTANCE = this;
        this.stage = stage;
        LogoUtils.getLogoFx().ifPresent(logo -> {
            stage.getIcons().add(logo);
        });

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/iceWindow.fxml"));
            root = loader.load();

            controller = loader.getController();
            controller.setAdapter(new UIAdapterImpl(IceAdapter.INSTANCE));
        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter - Debugger - Build: %s".formatted(IceAdapter.getVersion()));
        stage.setOnCloseRequest(event -> {
            if (controller != null) {
                controller.dispose();
            }
        });
        stage.setOnHidden(event -> {
            if (controller != null) {
                controller.dispose();
            }
        });

        if (Debug.ENABLE_DEBUG_WINDOW) {
            CompletableFuture.runAsync(
                    () -> runOnUIThread(stage::show),
                    CompletableFuture.delayedExecutor(Debug.DELAY_UI_MS, TimeUnit.MILLISECONDS));
        }

        log.info("Created debug window.");
    }

    public void showWindow() {
        runOnUIThread(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
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
        log.info("Launching ice window.");
        launch(IceWindow.class);
    }
}
