package com.faforever.iceadapter.dto.command.relay.manual.info;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class InfoRelayStatusCommand extends CommandBase {

    private boolean allowRelay;

    @Override
    public void execute(Peer peer) {
        if (peer == null) {
            log.warn("peer is null");
            return;
        }

        peer.setAllowRelay(allowRelay);
    }
}
