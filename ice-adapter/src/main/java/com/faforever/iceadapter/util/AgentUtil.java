package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;
import org.ice4j.ice.Agent;
import org.ice4j.ice.IceMediaStream;

@UtilityClass
public class AgentUtil {
    /**
     * Frees an ice4j agent without risking a deadlock on its relayed candidates.
     *
     * <p>{@link Agent#free()} shuts down the StunStack first, which closes every socket while holding the
     * NetAccessManager connector lock. Closing a relayed candidate socket needs that socket's send queue lock, which its
     * send thread holds while sending through the NetAccessManager, so both threads block forever. Removing the media
     * stream first closes the candidate sockets without holding the connector lock.
     */
    public void free(Agent agent, IceMediaStream mediaStream) {
        if (mediaStream != null) {
            agent.removeStream(mediaStream);
        }
        agent.free();
    }
}
