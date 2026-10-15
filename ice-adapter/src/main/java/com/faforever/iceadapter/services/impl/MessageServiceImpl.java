package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.util.TrayIcon;

public class MessageServiceImpl implements MessageService {
    @Override
    public void showMessage(String message) {
        TrayIcon.showMessage(message);
    }
}
