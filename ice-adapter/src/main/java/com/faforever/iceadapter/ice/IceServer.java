package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.util.PingWrapper;
import lombok.Getter;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class IceServer {
  private List<TransportAddress> stunAddresses = new ArrayList<>();
  private List<TransportAddress> turnAddresses = new ArrayList<>();
  private String turnUsername = "";
  private String turnCredential = "";
  private CompletableFuture<OptionalDouble> rtt;

  private static final Pattern urlPattern = Pattern.compile("(?<protocol>stun|turn):(?<host>(\\w|\\.)+)(:(?<port>\\d+))?(\\?transport=(?<transport>(tcp|udp)))?");

  public static IceServer fromData(Map<String, Object> iceServerData) {
    IceServer iceServer = new IceServer();

    if (iceServerData.containsKey("username")) {
      iceServer.turnUsername = (String) iceServerData.get("username");
    }
    if (iceServerData.containsKey("credential")) {
      iceServer.turnCredential = (String) iceServerData.get("credential");
    }

    if (iceServerData.containsKey("urls")) {
      List<String> urls;
      Object urlsData = iceServerData.get("urls");
      if (urlsData instanceof List) {
        urls = (List<String>) urlsData;
      } else {
        urls = Collections.singletonList((String) iceServerData.get("url"));
      }

      urls.stream()
              .map(urlPattern::matcher)
              .filter(Matcher::matches)
              .forEach(matcher -> {
                String host = matcher.group("host");
                int port = matcher.group("port") != null ? Integer.parseInt(matcher.group("port")) : 3478;
                Transport transport = matcher.group("protocol").equals("stun") ? Transport.UDP : Transport.parse(matcher.group("transport"));

                TransportAddress address = new TransportAddress(host, port, transport);
                (matcher.group("protocol").equals("stun") ? iceServer.getStunAddresses() : iceServer.getTurnAddresses()).add(address);

                iceServer.rtt = PingWrapper.getRTT(host).thenApply(OptionalDouble::of).exceptionally(ex -> OptionalDouble.empty());
              });
    }
    return iceServer;
  }
}
