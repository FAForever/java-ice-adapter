package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.fa.FaToPeerModule;
import com.faforever.iceadapter.ice.peer.modules.fa.PeerToFaModule;
import com.faforever.iceadapter.ice.peer.modules.info.RttCalculateModule;
import com.faforever.iceadapter.ice.peer.modules.other.*;
import com.faforever.iceadapter.ice.peer.modules.relay.auto.RelayBestRttPeerCheckerModule;
import com.faforever.iceadapter.ice.peer.modules.relay.auto.RelayWebRtcPeerToPeerSenderModule;
import com.faforever.iceadapter.ice.peer.modules.webrtc.WebRtcPeerToPeerListenerModule;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PeerModule {
    EVENT_BUS(0, EventBusModule::new),
    FA_SOCKET_MODULE(1, peer -> {
        var module = new FASocketModule(peer);
        module.firstStart();
        return module;
    }),
    FA_SENDER_MODULE(PeerToFaModule::new),
    FA_REPEATER_MODULE(peer -> {
        var module = new FaToPeerModule(peer);
        module.start();
        return module;
    }),
    CALCULATE_RTT(RttCalculateModule::new),
    CONNECTION_CHECKER_MODULE(PeerConnectivityCheckerModule::new),
    AUTO_SETTING_ALLOW_CANDIDATE(AutoSettingAllowCandidates::new),
    COMMAND_EXECUTE(CommandModule::new),
    AUTO_RELAY_CALCULATE_RTT(RelayBestRttPeerCheckerModule::new),
    INFO_STATUS_MODULE(InfoStatusModule::new),
    WEBRTC_PEER_TO_PEER_SENDER(RelayWebRtcPeerToPeerSenderModule::new),
    WEBRTC_PEER_TO_PEER_LISTENER(WebRtcPeerToPeerListenerModule::new);

    @Getter
    private static final List<PeerModule> sortedModules = Stream.of(PeerModule.values())
            .sorted(Comparator.comparingInt(PeerModule::getPriority))
            .toList();

    private final int priority;
    private final Function<Peer, ModuleBase> createModule;

    PeerModule(Function<Peer, ModuleBase> createModule) {
        this(10, createModule);
    }

    public ModuleBase createModule(Peer peer) {
        ModuleBase module = createModule.apply(peer);
        module.init();
        return module;
    }
}
