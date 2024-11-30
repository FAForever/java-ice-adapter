package com.faforever.iceadapter.util;

import com.faforever.iceadapter.AsyncService;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.debug.DebugWindow;
import com.faforever.iceadapter.debug.InfoWindow;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.net.URL;

@Slf4j
public class TrayIcon {

    public static final String FAF_LOGO_URL = "https://faforever.com/images/faf-logo.png";
    private static volatile java.awt.TrayIcon trayIcon;

    public static void create() {
        if (!isTrayIconSupported()) {
            log.warn("Tray icon not supported");
            return;
        }

        Image fafLogo = null;
        try {
            fafLogo = ImageIO.read(new URL(FAF_LOGO_URL));
        } catch (IOException e) {
            log.error("Couldn't load FAF tray icon logo from URL");
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
                AsyncService.runAsync(() -> {
                    if (InfoWindow.INSTANCE == null) {
                        log.info("Launching ICE adapter debug window");
                        Debug.ENABLE_INFO_WINDOW = true;
                        DebugWindow.launchApplication();
                    } else {
                        InfoWindow.INSTANCE.show();
                    }
                });
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
