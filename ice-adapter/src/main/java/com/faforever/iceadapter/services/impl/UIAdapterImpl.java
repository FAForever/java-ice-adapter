package com.faforever.iceadapter.services.impl;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceServer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.UIAdapter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UIAdapterImpl implements UIAdapter {

    private final IceAdapter iceAdapter;

    private final Map<Integer, PeerView> uiPeers = new ConcurrentHashMap<>();
    private final Map<Integer, WebRtcPeerView> uiWebRtcPeers = new ConcurrentHashMap<>();
    private final ObservableList<PeerView> peerInfoList = FXCollections.observableArrayList();
    private final ObservableList<WebRtcPeerView> webRtcPeerInfoList = FXCollections.observableArrayList();
    private final ObservableList<IceServerView> iceServersList = FXCollections.observableArrayList();

    private Optional<IceGameSession> getGameSession() {
        return Optional.ofNullable(iceAdapter.getGameSession());
    }

    @Override
    public String getVersion() {
        return IceAdapter.getVersion();
    }

    @Override
    public String getUsername() {
        return IceAdapter.getLogin();
    }

    @Override
    public int getUserId() {
        return IceAdapter.getId();
    }

    @Override
    public int getRpcPort() {
        return Optional.ofNullable(iceAdapter.getRpcService())
                .map(RPCService::getPort)
                .orElse(-1);
    }

    @Override
    public int getGpgNetPort() {
        return Optional.ofNullable(iceAdapter.getGpgNetServer())
                .map(GPGNetServer::getGpgNetPort)
                .orElse(-1);
    }

    @Override
    public int getLobbyPort() {
        return Optional.ofNullable(iceAdapter.getGpgNetServer())
                .map(GPGNetServer::getLobbyPort)
                .orElse(-1);
    }

    @Override
    public String getRpcServerStatus() {
        RPCService rpc = iceAdapter.getRpcService();
        return rpc != null ? rpc.getHost() : "Not found";
    }

    @Override
    public String getRpcClientStatus() {
        return "N/A";
    }

    @Override
    public String getGpgNetServerStatus() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.isServerRunning() ? "Running" : "Stopped";
    }

    @Override
    public String getGpgNetClientStatus() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.isConnected() ? "Connected" : "Disconnected";
    }

    @Override
    public String getGameState() {
        GPGNetServer server = iceAdapter.getGpgNetServer();
        return server != null && server.getGameState().isPresent()
                ? server.getGameState().get().name()
                : "UNKNOWN";
    }

    @Override
    public ObservableList<WebRtcPeerView> getWebRtcPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> peerIds = peers.keySet();

        if (!Objects.equals(uiWebRtcPeers.keySet(), peerIds)) {
            uiWebRtcPeers.keySet().removeIf(id -> !peerIds.contains(id));
        }

        for (Peer peer : peers.values()) {
            toWebRtcPeerInfo(peer);
        }

        boolean structureChanged = webRtcPeerInfoList.size() != peers.size();
        if (!structureChanged) {
            for (int i = 0; i < webRtcPeerInfoList.size(); i++) {
                if (!peerIds.contains(webRtcPeerInfoList.get(i).getPeerId().get())) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            List<WebRtcPeerView> sorted = peers.values().stream()
                    .sorted(Comparator.comparingInt(Peer::getRemoteId))
                    .map(this::toWebRtcPeerInfo)
                    .toList();
            webRtcPeerInfoList.setAll(sorted);
        }

        return webRtcPeerInfoList;
    }

    @Override
    public ObservableList<PeerView> getPeerInfoList() {
        Map<Integer, Peer> peers =
                getGameSession().map(IceGameSession::getPeers).orElse(Collections.emptyMap());

        Set<Integer> ids = peers.keySet();

        if (!Objects.equals(uiPeers.keySet(), ids)) {
            uiPeers.keySet().removeIf(id -> !ids.contains(id));
        }

        peers.values().forEach(peer -> {
            debug().peerStateChanged(peer);
            debug().peerConnectivityUpdate(peer);
            toPeerInfo(peer);
        });

        boolean structureChanged = peerInfoList.size() != peers.size();
        if (!structureChanged) {
            for (int i = 0; i < peerInfoList.size(); i++) {
                if (!ids.contains(peerInfoList.get(i).getId().get())) {
                    structureChanged = true;
                    break;
                }
            }
        }

        if (structureChanged) {
            List<PeerView> sorted = peers.values().stream()
                    .sorted(Comparator.comparingInt(Peer::getRemoteId))
                    .map(this::toPeerInfo)
                    .toList();
            peerInfoList.setAll(sorted);
        }

        return peerInfoList;
    }

    @Override
    public PeerView getPeerInfo(int id) {
        return uiPeers.get(id);
    }

    @Override
    public ObservableList<IceServerView> getIceServersList() {
        List<IceServer> servers =
                getGameSession().map(IceGameSession::getIceServers).orElse(Collections.emptyList());

        if (iceServersList.size() != servers.size()) {
            iceServersList.setAll(servers.stream().map(IceServerView::new).toList());
        }

        return iceServersList;
    }

    @Override
    public void setEnabledIceServer(IceServerView view, boolean enabled) {
        if (view == null) {
            return;
        }
        IceServer iceServer = view.getServer();
        iceServer.setEnabled(enabled);
        iceServer.setAuto(false);
    }

    private PeerView toPeerInfo(Peer peer) {
        PeerView info = uiPeers.computeIfAbsent(peer.getRemoteId(), id -> {
            PeerView uiInfo = new PeerView(peer.getRemoteId(), peer.getRemoteLogin());
            peer.addEventListener(uiInfo);
            return uiInfo;
        });

        info.update(peer);

        return info;
    }

    private WebRtcPeerView toWebRtcPeerInfo(Peer peer) {
        WebRtcPeerView info = uiWebRtcPeers.computeIfAbsent(
                peer.getRemoteId(), id -> new WebRtcPeerView(peer.getRemoteId(), peer.getRemoteLogin()));
        info.update(peer, isShowIpAddresses());

        return info;
    }

    @Override
    public void reconnect(PeerView peer) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(Peer::reconnect);
    }

    @Override
    public void setAdditionalPacketForwarding(PeerView peer, boolean enabled) {
        if (peer == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession()
                .flatMap(session -> session.getPeer(id))
                .ifPresent(p -> p.setAdditionalPacketForwarding(enabled));
    }

    @Override
    public void setCombination(PeerView peer, AllowCombination combination) {
        if (peer == null || combination == null) {
            return;
        }
        int id = peer.getId().get();
        getGameSession().flatMap(session -> session.getPeer(id)).ifPresent(p -> p.setCombination(combination, true));
    }

    @Override
    public boolean isShowIpAddresses() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isShowIpAddresses)
                .orElse(false);
    }

    @Override
    public boolean isShowAllowCombination() {
        return Optional.ofNullable(iceAdapter.getIceOptions())
                .map(IceOptions::isShowAllowCombination)
                .orElse(false);
    }

    @Override
    public void shutdown() {
        IceAdapter.close(0);
    }
}
