package com.faforever.iceadapter.ice.peer.modules.info;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RttCalculateModule implements ModuleBase, PeerEventListener {

    private final Peer peer;

    @Override
    public void init() {
        peer.addEventListener(this);
    }

    @Override
    public void start() {
        peer.setRtt(0.0f);
    }

    @Override
    public void onChangeEcho(Peer peer, Long lastEcho, long echo) {
        if (peer.isLocalOffer()) {
            calculateRttWhenAgentControlling(echo);
        }
    }

    private void calculateRttWhenAgentControlling(long echo) {
        long rttMs = System.currentTimeMillis() - echo;
        int rtt = (int) (rttMs);
        peer.setRtt(calculateRtt(rtt));
    }

    private float calculateRtt(int rtt) {
        float oldRtt = peer.getRtt();
        return oldRtt == 0 ? rtt : oldRtt * 0.8f + (float) rtt * 0.2f;
    }
}
