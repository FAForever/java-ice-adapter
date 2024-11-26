import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

@Slf4j
public class IceTest {

    private static final String COTURN_HOST = "geosearchef.de";
    private static final String COTURN_KEY = "B9UohFSnFeX1YQ7nQJsBe3MwWS4kx4FJ8TUBUDzYG23rLBCIeJMvgYfbRkeK";
    //    private static final String COTURN_HOST = "vmrbg145.informatik.tu-muenchen.de";
    //    private static final String COTURN_KEY = "banana";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.registerModule(new JavaTimeModule());
    }

    public static void main(String args[]) throws IOException {
        Scanner scan = new Scanner("false\n12345\n");

        String username = "iceTester" + System.currentTimeMillis() % 10000;
        System.out.printf("Username: %s\n", username);

        int timestamp = (int) (System.currentTimeMillis() / 1000) + 3600 * 24;
        String tokenName = String.format("%s:%s", timestamp, username);
        byte[] secret = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(
                    Charset.forName("cp1252").encode(COTURN_KEY).array(), "HmacSHA1"));
            secret = mac.doFinal(Charset.forName("cp1252").encode(tokenName).array());

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Could not build secret key", e);
            System.exit(-1);
        }
        String authToken = Base64.getEncoder().encodeToString(secret);

        Map<String, Object> map = new HashMap<>();
        map.put("credential", authToken);
        map.put("credentialType", "token");
        map.put("username", tokenName);

        //        int localPort

        TransportAddress[] turnAddresses = {
            new TransportAddress(COTURN_HOST, 3478, Transport.TCP),
            new TransportAddress(COTURN_HOST, 3478, Transport.UDP),
            //                String.format("turn:%s?transport=tcp", COTURN_HOST),
            //                String.format("turn:%s?transport=udp", COTURN_HOST)
        };

        TransportAddress[] stunAddresses = {
            new TransportAddress(COTURN_HOST, 3478, Transport.UDP),
            //                new TransportAddress("stun3.l.google.com", 19302, Transport.UDP)
            //                String.format("stun:%s", COTURN_HOST)
        };

        Agent agent = new Agent();

        System.out.printf("Controlling?(true|false)\n");
        agent.setControlling(scan.nextBoolean());

        Arrays.stream(turnAddresses)
                .map(a -> new TurnCandidateHarvester(
                        a,
                        new LongTermCredential(
                                /*String.format("%d:%s", ((System.currentTimeMillis() / 1000) + 3600*24), username)*/ (String)
                                        map.get("username"),
                                (String) map.get("credential"))))
                .forEach(agent::addCandidateHarvester);
        Arrays.stream(stunAddresses).map(StunCandidateHarvester::new).forEach(agent::addCandidateHarvester);

        System.out.printf("Preferred port?\n"); // host port
        int preferredPort = scan.nextInt();

        IceMediaStream mediaStream = agent.createMediaStream("mainStream");
        Component component = agent.createComponent(mediaStream, preferredPort, preferredPort, preferredPort + 100);

        // ------------------------------------------------------------
        // agent done
        // ------------------------------------------------------------

        // print candidates
        // may have to be done for multiple components
        int candidateIDFactory = 0;
        final List<CandidatePacket> candidatePackets = new ArrayList<>();

        for (LocalCandidate localCandidate : component.getLocalCandidates()) {
            String relAddr = null;
            int relPort = 0;

            if (localCandidate.getRelatedAddress() != null) {
                relAddr = localCandidate.getRelatedAddress().getHostAddress();
                relPort = localCandidate.getRelatedAddress().getPort();
            }

            CandidatePacket candidatePacket = new CandidatePacket(
                    localCandidate.getFoundation(),
                    localCandidate.getTransportAddress().getTransport().toString(),
                    localCandidate.getPriority(),
                    localCandidate.getTransportAddress().getHostAddress(),
                    localCandidate.getTransportAddress().getPort(),
                    CandidateType.valueOf(localCandidate.getType().name()),
                    agent.getGeneration(),
                    String.valueOf(candidateIDFactory++),
                    relAddr,
                    relPort);

            candidatePackets.add(candidatePacket);
        }

        Collections.sort(candidatePackets);

        CandidatesMessage localCandidatesMessage = new CandidatesMessage(
                0, 0 /*mocked*/, agent.getLocalPassword(), agent.getLocalUfrag(), candidatePackets);

        System.out.printf(
                "------------------------------------\n%s\n------------------------------------\n",
                objectMapper.writeValueAsString(localCandidatesMessage));

        // read candidates
        Socket socket = new Socket("localhost", 49456);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.writeUTF(objectMapper.writeValueAsString(localCandidatesMessage));
        out.flush();
        CandidatesMessage remoteCandidatesMessage = objectMapper.readValue(in.readUTF(), CandidatesMessage.class);

        // Set candidates
        mediaStream.setRemotePassword(remoteCandidatesMessage.password());
        mediaStream.setRemoteUfrag(remoteCandidatesMessage.ufrag());
        for (CandidatePacket remoteCandidatePacket : remoteCandidatesMessage.candidates()) {

            if (remoteCandidatePacket.generation() == agent.getGeneration()
                    && remoteCandidatePacket.ip() != null
                    && remoteCandidatePacket.port() > 0) {

                TransportAddress mainAddress = new TransportAddress(
                        remoteCandidatePacket.ip(),
                        remoteCandidatePacket.port(),
                        Transport.parse(remoteCandidatePacket.protocol().toLowerCase()));

                RemoteCandidate relatedCandidate = null;
                if (remoteCandidatePacket.relAddr() != null && remoteCandidatePacket.relPort() > 0) {
                    TransportAddress relatedAddr = new TransportAddress(
                            remoteCandidatePacket.relAddr(),
                            remoteCandidatePacket.relPort(),
                            Transport.parse(remoteCandidatePacket.protocol().toLowerCase()));
                    relatedCandidate = component.findRemoteCandidate(relatedAddr);
                }

                RemoteCandidate remoteCandidate = new RemoteCandidate(
                        mainAddress,
                        component,
                        CandidateType.parse(remoteCandidatePacket.type().toString()),
                        remoteCandidatePacket.foundation(),
                        remoteCandidatePacket.priority(),
                        relatedCandidate);

                if (remoteCandidate.getType().equals(CandidateType.RELAYED_CANDIDATE)) // DEBUGGING: turn only
                component.addRemoteCandidate(remoteCandidate);
            }
        }

        agent.startConnectivityEstablishment();

        while (agent.getState() != IceProcessingState.TERMINATED) { // TODO include more?
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        while ("".isEmpty()) {
            component
                    .getSelectedPair()
                    .getIceSocketWrapper()
                    .send(new DatagramPacket(
                            "Aeon is the worst faction on this planet!".getBytes(),
                            0,
                            4,
                            InetAddress.getByName(component
                                    .getSelectedPair()
                                    .getRemoteCandidate()
                                    .getHostAddress()
                                    .getHostAddress()),
                            component
                                    .getSelectedPair()
                                    .getRemoteCandidate()
                                    .getHostAddress()
                                    .getPort()));
            byte[] data = new byte[1024];
            component.getSelectedPair().getIceSocketWrapper().receive(new DatagramPacket(data, data.length));
            System.out.println("Got data: " + new String(data));
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        agent.free();
    }
}
