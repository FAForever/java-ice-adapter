package com.faforever.iceadapter.util;

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
public class TrayIconWrapper {

    public static final String FAF_LOGO_URL = "https://faforever.com/images/faf-logo.png";
    private java.awt.TrayIcon trayIcon;

    public TrayIconWrapper() {

        if (!isTrayIconSupported()) {
            log.warn("Tray icon not supported");
            trayIcon = null;
            return;
        }

        Image fafLogo = null;
        try {
            fafLogo = ImageIO.read(new URL(FAF_LOGO_URL));
        } catch (IOException e) {
            log.error("Couldn't load FAF tray icon logo from URL");
            return;
        }


        trayIcon = new java.awt.TrayIcon(fafLogo, "FAForever Connection ICE Adapter");
        fafLogo = fafLogo.getScaledInstance(trayIcon.getSize().width, trayIcon.getSize().height, Image.SCALE_SMOOTH);
        trayIcon.setImage(fafLogo);

        trayIcon.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent mouseEvent) {
            }

            @Override
            public void mousePressed(MouseEvent mouseEvent) {
                new Thread(() -> {
                    if (InfoWindow.INSTANCE == null) {
                        Debug.ENABLE_INFO_WINDOW = true;
                        DebugWindow.launchApplication();
                    } else {
                        InfoWindow.INSTANCE.show();
                    }
                }).start();
            }

            @Override
            public void mouseReleased(MouseEvent mouseEvent) {
            }

            @Override
            public void mouseEntered(MouseEvent mouseEvent) {
            }

            @Override
            public void mouseExited(MouseEvent mouseEvent) {
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            log.error("Tray icon could not be added", e);
        }

        log.info("Created tray icon");
    }

    public void showMessage(String message) {
        if (trayIcon != null) {
            SwingUtilities.invokeLater(() -> {
                trayIcon.displayMessage("FAForever Connection ICE Adapter", message, java.awt.TrayIcon.MessageType.INFO);
            });
        }
    }

    public void close() {
        if(trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    public static boolean isTrayIconSupported() {
        return SystemTray.isSupported();
    }

}
