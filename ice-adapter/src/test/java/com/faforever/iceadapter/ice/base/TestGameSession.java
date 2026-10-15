package com.faforever.iceadapter.ice.base;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.services.RpcConnection;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Test extension of {@link GameSession} that allows overriding lobby port
 * and disabling specific peer modules.
 */
@Setter
@Getter
public class TestGameSession extends GameSession {
    private int lobbyPort = 0;
    private Set<PeerModule> disabledModules;

    public TestGameSession(RpcConnection rpcConnection, IceOptions options, Set<PeerModule> disabledModules) {
        super(rpcConnection, options);
        this.disabledModules = disabledModules;
    }

    @Override
    protected Set<PeerModule> getAdditionalDisabledModules() {
        return disabledModules;
    }

    @Override
    public int getLobbyPort() {
        return lobbyPort;
    }
}
