import com.faforever.iceadapter.util.latencyestimation.LatencyEstimator;

import java.util.concurrent.ExecutionException;

public class LatencyEstimatorTest {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        double latency = LatencyEstimator.getLatency("faforever.com")
                .exceptionally(e -> 10000.0)
                .get();

        System.out.println("Latency to host is " + latency);
    }
}
