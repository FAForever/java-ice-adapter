package com.faforever.iceadapter.ice.peer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class RelayPing {
    private static final int TIME_ACTUAL = 3;

    private final String remoteLogin;
    private Instant ago = Instant.now();
    private float rtt = 0.0f;

    public void updateRtt(float newRtt) {
        ago = Instant.now();
        rtt = newRtt;
    }

    public boolean isActual() {
        return ChronoUnit.SECONDS.between(ago, Instant.now()) < TIME_ACTUAL;
    }
}
