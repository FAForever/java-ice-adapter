package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.IceServer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@Data
public class IceServerView {
    private final IceServer server;
    private final StringProperty type;
    private final StringProperty transport;
    private final StringProperty address;
    private final StringProperty rtt;
    private final BooleanProperty enabled;

    public IceServerView(IceServer server) {
        this.server = server;
        this.type = new SimpleStringProperty(server.getType().name());
        this.transport =
                new SimpleStringProperty(server.getAddress().getTransport().name());
        this.address = new SimpleStringProperty(
                server.getAddress().getHostName() + ":" + server.getAddress().getPort());
        this.rtt = new SimpleStringProperty(server.strTripTime());
        this.enabled = new SimpleBooleanProperty(server.isEnabled());
    }
}
