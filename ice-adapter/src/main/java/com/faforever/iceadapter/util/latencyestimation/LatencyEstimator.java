package com.faforever.iceadapter.util.latencyestimation;

import com.faforever.iceadapter.IceAdapter;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LatencyEstimator {

    private static final List<String> FAF_ESTIMATION_HOSTNAMES = Collections.emptyList();
    private static final List<String> ECHO_ESTIMATION_HOSTNAMES = Collections.singletonList("faforever.com");

    public static CompletableFuture<Double> getLatency(String hostname) {
        if(ECHO_ESTIMATION_HOSTNAMES.contains(hostname)) {
            System.err.println("Using geosearchef.de for echo latency estimation instead!!!!");
            hostname = "geosearchef.de"; // TODO: REMOVE AFTER DEPLOYMENT; FOR TESTING PURPOSES ONLY
            return EchoLatencyEstimator.getLatency(hostname);
        } else if(FAF_ESTIMATION_HOSTNAMES.contains(hostname)) {
            return FafLatencyEstimator.getLatency(hostname);
        } else {
            return PingWrapper.getLatency(hostname, IceAdapter.PING_COUNT);
        }
    }

}
