package com.faforever.iceadapter.dto.command;

import static com.faforever.iceadapter.ice.peer.modules.other.CommandModule.COMMAND_BASE;

import com.faforever.iceadapter.dto.command.relay.auto.info.RelayPingCommand;
import com.faforever.iceadapter.dto.command.relay.manual.info.InfoRelayStatusCommand;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.util.ObjectMapperUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InfoRelayStatusCommand.class, name = "info_relay_status"),
    @JsonSubTypes.Type(value = RelayPingCommand.class, name = "relay_ping"),
})
public abstract class CommandBase {
    private static final boolean COMPRESSION = true;

    public abstract void execute(Peer peer);

    @JsonIgnore
    public boolean isOnlyDirect() {
        return false;
    }

    public byte[] bytes() {
        return ObjectMapperUtil.toBytesAndAddFirstByte((byte) COMMAND_BASE, this, COMPRESSION);
    }

    public static CommandBase initCommand(byte[] bytes) {
        return ObjectMapperUtil.fromBytesAndWithOutFirstByte(bytes, CommandBase.class, COMPRESSION);
    }
}
