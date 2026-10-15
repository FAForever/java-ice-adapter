package com.faforever.iceadapter.util;

import com.faforever.iceadapter.LogoUtils;
import com.faforever.iceadapter.debug.Debug;
import com.faforever.iceadapter.ui.InfoWindow;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrayIcon {

    private static volatile java.awt.TrayIcon trayIcon;

    public static void create() {
        if (!isTrayIconSupported()) {
            log.warn("Tray icon not supported");
            return;
        }

        Image fafLogo = LogoUtils.getLogo();
        if (fafLogo == null) {
            return;
        }

        Dimension dimension = SystemTray.getSystemTray().getTrayIconSize();
        fafLogo = fafLogo.getScaledInstance(dimension.width, dimension.height, Image.SCALE_SMOOTH);

        trayIcon = new java.awt.TrayIcon(fafLogo, "FAForever Connection ICE Adapter");

        trayIcon.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent mouseEvent) {}

            @Override
            public void mousePressed(MouseEvent mouseEvent) {
                if (InfoWindow.INSTANCE == null) {
                    log.info("Launching ICE adapter debug window");
                    Debug.ENABLE_INFO_WINDOW = true;
                    InfoWindow.launch();
                } else {
                    InfoWindow.INSTANCE.showWindow();
                }
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
        if (isTrayIconSupported() && trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
                log.info("Tray icon removed");
            } catch (Exception e) {
                log.error("Error removing tray icon", e);
            } finally {
                trayIcon = null;
            }
        }
    }

    public static boolean isTrayIconSupported() {
        return SystemTray.isSupported();
    }
}
