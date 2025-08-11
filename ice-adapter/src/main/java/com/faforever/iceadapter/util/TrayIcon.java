package com.faforever.iceadapter.util;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.debug.DebugWindow;
import com.faforever.iceadapter.debug.InfoWindow;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrayIcon {

    public static final String FAF_LOGO_FILE = "faf-logo.png";
    private static volatile java.awt.TrayIcon trayIcon;

    public static void create() {
        if (!isTrayIconSupported()) {
            log.warn("Tray icon not supported");
            return;
        }

        Image fafLogo = null;
        try (final InputStream imageStream = TrayIcon.class.getClassLoader().getResourceAsStream(FAF_LOGO_FILE)) {
            if (imageStream == null) {
                log.error("Couldn't find '{}' in resource folder", FAF_LOGO_FILE);
                return;
            }
            fafLogo = ImageIO.read(imageStream);
        } catch (IOException e) {
            log.error("Couldn't load FAF tray icon logo from resource folder", e);
            return;
        }

        fafLogo = fafLogo.getScaledInstance(
                new java.awt.TrayIcon(fafLogo).getSize().width,
                new java.awt.TrayIcon(fafLogo).getSize().height,
                Image.SCALE_SMOOTH);

        trayIcon = new java.awt.TrayIcon(fafLogo, "FAForever Connection ICE Adapter");

        trayIcon.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent mouseEvent) {}

            @Override
            public void mousePressed(MouseEvent mouseEvent) {
                CompletableFuture.runAsync(
                        () -> {
                            if (InfoWindow.INSTANCE == null) {
                                log.info("Launching ICE adapter debug window");
                                Debug.ENABLE_INFO_WINDOW = true;
                                DebugWindow.launchApplication();
                            } else {
                                InfoWindow.INSTANCE.show();
                            }
                        },
                        IceAdapter.getExecutor());
            }

            @Override
            public void mouseReleased(MouseEvent mouseEvent) {}

            @Override
            public void mouseEntered(MouseEvent mouseEvent) {}

            @Override
            public void mouseExited(MouseEvent mouseEvent) {}
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            log.error("Tray icon could not be added", e);
        }

        log.info("Created tray icon");
    }

    public static void showMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null) {
                trayIcon.displayMessage(
                        "FAForever Connection ICE Adapter", message, java.awt.TrayIcon.MessageType.INFO);
            }
        });
    }

    public static void close() {
        SystemTray.getSystemTray().remove(trayIcon);
    }

    public static boolean isTrayIconSupported() {
        return SystemTray.isSupported();
    }
}
