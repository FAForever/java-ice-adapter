package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import lombok.Data;
import org.ice4j.TransportAddress;

@Data
public class IceServer {
    private List<TransportAddress> stunAddresses = new ArrayList<>();
    private List<TransportAddress> turnAddresses = new ArrayList<>();
    private String turnUsername = "";
    private String turnCredential = "";
    private CompletableFuture<OptionalDouble> roundTripTime = CompletableFuture.completedFuture(OptionalDouble.empty());

    public static final Pattern urlPattern = Pattern.compile(
            "(?<protocol>stun|turn):(?<host>(\\w|\\.)+)(:(?<port>\\d+))?(\\?transport=(?<transport>(tcp|udp)))?");

    public boolean hasAcceptableLatency() {
        OptionalDouble rtt = this.getRoundTripTime().join();
        return rtt.isEmpty() || rtt.getAsDouble() < IceAdapter.getAcceptableLatency();
    }
}
