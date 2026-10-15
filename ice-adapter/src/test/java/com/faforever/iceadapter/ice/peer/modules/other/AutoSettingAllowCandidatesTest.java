package com.faforever.iceadapter.ice.peer.modules.other;

import static org.junit.jupiter.api.Assertions.*;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AutoSettingAllowCandidates Unit Tests")
class AutoSettingAllowCandidatesTest {

    private Peer peer;

    private Peer createTestPeer(Set<PeerModule> disabledModules) {
        Peer p = new Peer(1, 2, "TestPlayer", true, 0, 0, false, disabledModules);
        p.init();
        p.initModules();
        return p;
    }

    @AfterEach
    void tearDown() {
        if (peer != null) {
            peer.close();
        }
    }

    @Test
    @DisplayName("Should automatically cycle AllowCombination on connection loss")
    void testCycleAllowCombinationOnConnectionLost() throws InterruptedException {
        peer = createTestPeer(Set.of());
        assertEquals(AllowCombination.ALL, peer.getCombination(), "Initial combination should be ALL");

        // First connection lost -> REFLEXIVE_RELAY
        peer.lostConnect();
        assertEquals(AllowCombination.REFLEXIVE_RELAY, peer.getCombination());

        // Wait past MIN_CHANGE_INTERVAL_MS debounce (1000ms)
        Thread.sleep(1050);

        // Second connection lost -> HOST_RELAY
        peer.lostConnect();
        assertEquals(AllowCombination.HOST_RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Third connection lost -> RELAY
        peer.lostConnect();
        assertEquals(AllowCombination.RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Fourth connection lost -> wraps back to ALL
        peer.lostConnect();
        assertEquals(AllowCombination.ALL, peer.getCombination());
    }

    @Test
    @DisplayName("Should debounce rapid connection lost calls within 1 second")
    void testDebounceRapidConnectionLost() {
        peer = createTestPeer(Set.of());
        assertEquals(AllowCombination.ALL, peer.getCombination());

        // Rapid lostConnect calls
        peer.lostConnect();
        assertEquals(AllowCombination.REFLEXIVE_RELAY, peer.getCombination());

        // Immediate next call should be ignored by debouncer
        peer.lostConnect();
        assertEquals(
                AllowCombination.REFLEXIVE_RELAY, peer.getCombination(), "Rapid second lostConnect should be ignored");
    }

    @Test
    @DisplayName("Should not change combination when disabled manually via setCombination")
    void testDisabledOnManualCombinationSet() throws InterruptedException {
        peer = createTestPeer(Set.of());
        assertEquals(AllowCombination.ALL, peer.getCombination());

        // User manually sets combination in UI (disableAutomatic = true)
        peer.setCombination(AllowCombination.HOST_RELAY, true);
        assertEquals(AllowCombination.HOST_RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Connection lost should NOT change combination because module is disabled
        peer.lostConnect();
        assertEquals(AllowCombination.HOST_RELAY, peer.getCombination(), "Combination must not change when disabled");
    }

    @Test
    @DisplayName("Module should be enabled by default when not in disabledModules")
    void testModuleLifecycle() {
        peer = createTestPeer(Set.of());
        var moduleOpt = peer.getModule(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE, AutoSettingAllowCandidates.class);
        assertTrue(moduleOpt.isPresent(), "AUTO_SETTING_ALLOW_CANDIDATE module should be present in Peer");
        assertTrue(moduleOpt.get().isEnabled(), "Module should be enabled by default");

        moduleOpt.get().disable();
        assertFalse(moduleOpt.get().isEnabled());

        moduleOpt.get().enable();
        assertTrue(moduleOpt.get().isEnabled());
    }

    @Test
    @DisplayName("Should automatically cycle AllowCombination on failed connection attempt (DISCONNECTED state)")
    void testCycleAllowCombinationOnFailedConnectionAttempt() throws InterruptedException {
        peer = createTestPeer(Set.of());
        assertEquals(AllowCombination.ALL, peer.getCombination());

        // Connection attempt fails during CHECKING -> DISCONNECTED
        peer.setIceState(IceState.CHECKING);
        peer.setIceState(IceState.DISCONNECTED);
        assertEquals(AllowCombination.REFLEXIVE_RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Next attempt fails
        peer.setIceState(IceState.CHECKING);
        peer.setIceState(IceState.DISCONNECTED);
        assertEquals(AllowCombination.HOST_RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Next attempt fails
        peer.setIceState(IceState.AWAITING_CANDIDATES);
        peer.setIceState(IceState.DISCONNECTED);
        assertEquals(AllowCombination.RELAY, peer.getCombination());

        Thread.sleep(1050);

        // Fails in RELAY -> wraps back to ALL
        peer.setIceState(IceState.CHECKING);
        peer.setIceState(IceState.DISCONNECTED);
        assertEquals(AllowCombination.ALL, peer.getCombination());
    }

    @Test
    @DisplayName("Should not double-change combination when both lostConnect and DISCONNECTED state fire rapidly")
    void testDebounceStateChangeAfterLostConnect() {
        peer = createTestPeer(Set.of());
        assertEquals(AllowCombination.ALL, peer.getCombination());

        // Normal disconnect sequence: lostConnect() followed by setIceState(DISCONNECTED)
        peer.lostConnect();
        peer.setIceState(IceState.DISCONNECTED);

        assertEquals(
                AllowCombination.REFLEXIVE_RELAY,
                peer.getCombination(),
                "Rapid lostConnect and DISCONNECTED state change should be debounced into single combination switch");
    }

    @Test
    @DisplayName("Should not change combination on state change when disabled manually")
    void testDisabledOnFailedAttempt() {
        peer = createTestPeer(Set.of());
        peer.setCombination(AllowCombination.HOST_RELAY, true);

        peer.setIceState(IceState.CHECKING);
        peer.setIceState(IceState.DISCONNECTED);
        assertEquals(AllowCombination.HOST_RELAY, peer.getCombination(), "Combination must not change when disabled");
    }
}
