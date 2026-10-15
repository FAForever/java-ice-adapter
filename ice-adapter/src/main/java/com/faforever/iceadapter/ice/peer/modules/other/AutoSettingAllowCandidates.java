package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AutoSettingAllowCandidates implements ModuleBase, PeerEventListener {
    private static final List<AllowCombination> combinations = List.of(AllowCombination.values());
    private static final long MIN_CHANGE_INTERVAL_MS = 1000;

    private final Peer peer;
    private long lastChangeTime = 0;
    private boolean enabled = true;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        if (newState == IceState.DISCONNECTED && isEnabled() && !peer.isClosing()) {
            changeCombination();
        }
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (isEnabled() && !peer.isClosing()) {
            changeCombination();
        }
    }

    private synchronized void changeCombination() {
        long now = System.currentTimeMillis();
        if (now - lastChangeTime < MIN_CHANGE_INTERVAL_MS) {
            log.debug(
                    "Skipping AutoSettingAllowCandidates for peer {}, last change was {}ms ago",
                    peer.getPeerIdentifier(),
                    now - lastChangeTime);
            return;
        }
        lastChangeTime = now;

        AllowCombination current = peer.getCombination();
        int currentIndex = current != null ? combinations.indexOf(current) : -1;
        int nextIndex = (currentIndex + 1) % combinations.size();
        AllowCombination nextCombination = combinations.get(nextIndex);
        log.info(
                "AutoSettingAllowCandidates for peer {}: switching combination from {} to {}",
                peer.getPeerIdentifier(),
                current,
                nextCombination);
        peer.setCombination(nextCombination);
    }

    @Override
    public Boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }
}
