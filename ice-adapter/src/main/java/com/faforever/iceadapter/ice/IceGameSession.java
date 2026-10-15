package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.webrtc.WebRtcConnectionFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IceGameSession {

    Map<Integer, Peer> getPeers();

    Optional<Peer> getPeer(int peerId);

    List<IceServer> getIceServers();

    boolean isGameEnded();

    int getLobbyPort();

    IceOptions getOptions();

    WebRtcConnectionFactory getWebRtcConnectionFactory();
}
