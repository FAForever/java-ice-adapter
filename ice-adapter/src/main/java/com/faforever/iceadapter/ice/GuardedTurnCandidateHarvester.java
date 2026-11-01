package com.faforever.iceadapter.ice;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.attribute.Attribute;
import org.ice4j.attribute.ErrorCodeAttribute;
import org.ice4j.attribute.XorPeerAddressAttribute;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvest;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.message.Message;
import org.ice4j.message.Request;
import org.ice4j.message.Response;
import org.ice4j.security.LongTermCredential;
import org.ice4j.socket.RelayedCandidateDatagramSocket;
import org.ice4j.stack.TransactionID;

/**
 * A {@link TurnCandidateHarvester} that works around two ice4j TURN client defects which caused endless
 * CreatePermission storms against coturn, Xirsys and Cloudflare:
 * <ul>
 *   <li>ice4j's {@link RelayedCandidateDatagramSocket} immediately re-sends a CreatePermission request whenever the
 *   previous one failed, without backoff or limit, for as long as a packet for that peer is queued. A single packet to
 *   a peer the TURN server refuses (e.g. 403 Forbidden IP) results in thousands of requests per second.
 *   Failed permissions are now retried with exponential backoff and given up after a few attempts; refusals that can
 *   never succeed are not retried at all.</li>
 *   <li>Relayed candidates were paired with remote private/link-local/loopback candidates. Public TURN servers refuse
 *   to relay to those, so every such pair only produced failing permission requests. They are no longer paired.</li>
 * </ul>
 *
 * <p>Reported upstream as <a href="https://github.com/jitsi/ice4j/issues/319">jitsi/ice4j#319</a>. This class can be
 * removed once ice4j fixes the retry loop.
 */
@Slf4j
public class GuardedTurnCandidateHarvester extends TurnCandidateHarvester {

    private static final int MAX_PERMISSION_ATTEMPTS = 5;
    private static final long INITIAL_PERMISSION_RETRY_DELAY_MS = 500;

    public GuardedTurnCandidateHarvester(TransportAddress turnServer, LongTermCredential longTermCredential) {
        super(turnServer, longTermCredential);
    }

    @Override
    protected TurnCandidateHarvest createHarvest(HostCandidate hostCandidate) {
        return new GuardedTurnCandidateHarvest(this, hostCandidate);
    }

    /**
     * Whether a TURN server can be expected to relay to the given address. Public TURN servers refuse peers in
     * private, loopback, link-local, CGNAT and unique local ranges.
     */
    static boolean isRelayablePeerAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10 carrier-grade NAT
            return !((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64);
        }
        // fc00::/7 unique local
        return (bytes[0] & 0xFE) != 0xFC;
    }

    private static class GuardedTurnCandidateHarvest extends TurnCandidateHarvest {

        private final Map<TransportAddress, Integer> failedPermissionAttempts = new ConcurrentHashMap<>();

        GuardedTurnCandidateHarvest(TurnCandidateHarvester harvester, HostCandidate hostCandidate) {
            super(harvester, hostCandidate);
        }

        @Override
        protected RelayedCandidate createRelayedCandidate(
                TransportAddress transportAddress, TransportAddress mappedAddress) {
            return new RelayedCandidate(transportAddress, this, mappedAddress) {
                @Override
                public boolean canReach(Candidate<?> dst) {
                    return super.canReach(dst)
                            && isRelayablePeerAddress(dst.getTransportAddress().getAddress());
                }
            };
        }

        @Override
        protected void processSuccess(Response response, Request request, TransactionID transactionID) {
            if (request.getMessageType() == Message.CREATEPERMISSION_REQUEST) {
                failedPermissionAttempts.remove(getPeerAddress(request));
            }
            super.processSuccess(response, request, transactionID);
        }

        /**
         * ice4j's relayed socket re-sends a failed CreatePermission as soon as it is told about the failure. We delay
         * telling it (exponential backoff) and stop telling it altogether once retrying is pointless, which leaves the
         * channel in "binding" state so no further requests for that peer are sent.
         */
        @Override
        protected boolean processErrorOrFailure(Response response, Request request, TransactionID transactionID) {
            if (request == null
                    || request.getMessageType() != Message.CREATEPERMISSION_REQUEST
                    || !(transactionID.getApplicationData() instanceof RelayedCandidateDatagramSocket)) {
                return super.processErrorOrFailure(response, request, transactionID);
            }

            TransportAddress peerAddress = getPeerAddress(request);
            int attempts = failedPermissionAttempts.merge(peerAddress, 1, Integer::sum);
            int errorCode = getErrorCode(response);

            if (isPermanentPermissionError(errorCode) || attempts >= MAX_PERMISSION_ATTEMPTS) {
                log.warn(
                        "TURN server {} refused permission for peer {} (error {}, attempt {}), giving up on this peer",
                        harvester.stunServer,
                        peerAddress,
                        errorCode == 0 ? "timeout" : errorCode,
                        attempts);
                return false;
            }

            long delay = INITIAL_PERMISSION_RETRY_DELAY_MS << (attempts - 1);
            log.debug(
                    "TURN server {} refused permission for peer {} (error {}), retrying in {} ms",
                    harvester.stunServer,
                    peerAddress,
                    errorCode == 0 ? "timeout" : errorCode,
                    delay);
            CompletableFuture.runAsync(
                    () -> super.processErrorOrFailure(response, request, transactionID),
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));
            return false;
        }

        private static TransportAddress getPeerAddress(Request request) {
            var attribute = (XorPeerAddressAttribute) request.getAttribute(Attribute.XOR_PEER_ADDRESS);
            return attribute.getAddress(request.getTransactionID());
        }

        /**
         * @return the STUN error code of the response, or 0 if there is none (e.g. the transaction timed out)
         */
        private static int getErrorCode(Response response) {
            if (response == null
                    || !(response.getAttribute(Attribute.ERROR_CODE) instanceof ErrorCodeAttribute errorCode)) {
                return 0;
            }
            return errorCode.getErrorCode();
        }

        private static boolean isPermanentPermissionError(int errorCode) {
            return errorCode == ErrorCodeAttribute.FORBIDDEN
                    || errorCode == ErrorCodeAttribute.PEER_ADDRESS_FAMILY_MISMATCH
                    || errorCode == ErrorCodeAttribute.UNAUTHORIZED
                    || errorCode == ErrorCodeAttribute.BAD_REQUEST;
        }
    }
}
