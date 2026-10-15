package com.faforever.iceadapter.services;

import com.faforever.iceadapter.dto.*;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import javafx.collections.ObservableList;

/**
 * Interface for UI components to interact with the ICE adapter and display connection/debug information.
 * Provides methods to retrieve application state, peer information, and control ICE agent behavior.
 */
public interface UIAdapter {

    /**
     * Returns the current version of the ICE adapter.
     *
     * @return version string
     */
    String getVersion();

    /**
     * Returns the username of the connected user.
     *
     * @return username
     */
    String getUsername();

    /**
     * Returns the user ID of the connected user.
     *
     * @return user ID
     */
    int getUserId();

    /**
     * Returns the RPC server port used by the adapter.
     *
     * @return RPC port number
     */
    int getRpcPort();

    /**
     * Returns the GPGNet server port used by the adapter.
     *
     * @return GPGNet port number
     */
    int getGpgNetPort();

    /**
     * Returns the lobby server port used by the adapter.
     *
     * @return lobby port number
     */
    int getLobbyPort();

    /**
     * Returns the current status of the RPC server.
     *
     * @return RPC server status as string
     */
    String getRpcServerStatus();

    /**
     * Returns the current status of the RPC client.
     *
     * @return RPC client status as string
     */
    String getRpcClientStatus();

    /**
     * Returns the current status of the GPGNet server.
     *
     * @return GPGNet server status as string
     */
    String getGpgNetServerStatus();

    /**
     * Returns the current status of the GPGNet client.
     *
     * @return GPGNet client status as string
     */
    String getGpgNetClientStatus();

    /**
     * Returns the current game state.
     *
     * @return game state as string
     */
    String getGameState();

    /**
     * Returns an observable list of WebRTC statistics for peers using WebRTC transport.
     *
     * @return observable list of WebRTC peer info objects
     */
    ObservableList<WebRtcPeerView> getWebRtcPeerInfoList();

    /**
     * Returns an observable list of peer connection details.
     * This list can be bound to UI components for real-time updates.
     *
     * @return observable list of peer info objects
     */
    ObservableList<PeerView> getPeerInfoList();

    PeerView getPeerInfo(int id);

    /**
     * Returns an observable list of ICE server configurations used by the adapter.
     * This list includes STUN, TURN, and other relay servers employed in the ICE negotiation process.
     * The list can be bound directly to UI components (e.g., tables or dropdowns) to display or modify
     * active ICE server settings in real time.
     *
     * @return observable list of {@link IceServerView} objects representing ICE server configurations
     */
    ObservableList<IceServerView> getIceServersList();

    void setEnabledIceServer(IceServerView iceServer, boolean enabled);

    /**
     * Requests reconnection to the specified peer.
     *
     * @param peer the peer to reconnect to
     */
    void reconnect(PeerView peer);

    void setAdditionalPacketForwarding(PeerView peer, boolean enabled);

    void setCombination(PeerView peer, AllowCombination combination);

    boolean isShowIpAddresses();

    boolean isShowAllowCombination();

    default boolean isAllowCombination() {
        return isShowAllowCombination();
    }

    /**
     * Initiates a graceful shutdown of the adapter and all associated components.
     */
    void shutdown();
}
