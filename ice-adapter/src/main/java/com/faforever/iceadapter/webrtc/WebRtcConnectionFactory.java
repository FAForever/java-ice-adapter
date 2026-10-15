package com.faforever.iceadapter.webrtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import lombok.extern.slf4j.Slf4j;

/**
 * Singleton that initializes PeerConnectionFactory once and provides RTCPeerConnection instances.
 */
@Slf4j
public final class WebRtcConnectionFactory {

    private static volatile WebRtcConnectionFactory instance;
    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDeviceModule;

    private WebRtcConnectionFactory() {}

    public static WebRtcConnectionFactory getInstance() {
        WebRtcConnectionFactory result = instance;
        if (result == null) {
            synchronized (WebRtcConnectionFactory.class) {
                result = instance;
                if (result == null) {
                    result = new WebRtcConnectionFactory();
                    result.init();
                    instance = result;
                }
            }
        }
        return result;
    }

    private void init() {
        try {
            try {
                Logging.addLogSink(Logging.Severity.INFO, (severity, message) -> {
                    switch (severity) {
                        case VERBOSE -> log.trace("[WebRTC] {}", message);
                        case INFO -> log.debug("[WebRTC] {}", message);
                        case WARNING -> log.warn("[WebRTC] {}", message);
                        case ERROR -> log.error("[WebRTC] {}", message);
                        default -> {}
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to register WebRTC log sink", e);
            }

            audioDeviceModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
            factory = new PeerConnectionFactory(audioDeviceModule);
            log.info("WebRtcConnectionFactory initialized");
        } catch (Exception e) {
            log.error("Failed to initialize WebRtcConnectionFactory", e);
            throw new RuntimeException("WebRTC initialization failed", e);
        }
    }

    public synchronized PeerConnectionFactory getFactory() {
        if (factory == null) {
            throw new IllegalStateException("WebRtcConnectionFactory is shut down or not initialized");
        }
        return factory;
    }

    /**
     * Shutdown the factory (called on adapter close).
     */
    public void shutdown() {
        synchronized (WebRtcConnectionFactory.class) {
            if (factory != null) {
                try {
                    factory.dispose();
                } catch (Throwable e) {
                    log.warn("Error disposing PeerConnectionFactory", e);
                }
                factory = null;
            }
            if (audioDeviceModule != null) {
                try {
                    audioDeviceModule.dispose();
                } catch (Throwable e) {
                    log.warn("Error disposing AudioDeviceModule", e);
                }
                audioDeviceModule = null;
            }
            instance = null;
            log.info("WebRtcConnectionFactory shut down");
        }
    }
}
