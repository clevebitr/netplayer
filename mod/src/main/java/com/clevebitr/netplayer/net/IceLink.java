package com.clevebitr.netplayer.net;

import com.clevebitr.netplayer.core.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * One ICE session (host candidates plus STUN discovered ones). Candidates are exchanged once over
 * the signaling channel; the resulting socket carries the tunnel.
 */
public final class IceLink implements AutoCloseable {
    public static final int MAX_DATAGRAM = 1500;

    private final Agent agent;
    private final IceMediaStream stream;
    private final Component component;
    private volatile boolean closed;

    private static final java.util.Set<String> QUIET = new java.util.HashSet<>();

    static {
        for (String name : List.of("org.ice4j", "org.jitsi")) {
            java.util.logging.Logger.getLogger(name).setLevel(java.util.logging.Level.WARNING);
            QUIET.add(name);
        }
    }

    public IceLink(boolean controlling, List<String> stunServers) throws Exception {
        agent = new Agent();
        agent.setControlling(controlling);
        agent.setTrickling(false);
        for (String stun : stunServers) {
            TransportAddress address = parse(stun);
            if (address != null) {
                agent.addCandidateHarvester(new StunCandidateHarvester(address));
            }
        }
        stream = agent.createMediaStream("netplayer");
        component = agent.createComponent(stream, 0, 0, 0);
    }

    private static TransportAddress parse(String text) {
        try {
            int colon = text.lastIndexOf(':');
            String host = colon > 0 ? text.substring(0, colon) : text;
            int port = colon > 0 ? Integer.parseInt(text.substring(colon + 1)) : 3478;
            return new TransportAddress(InetAddress.getByName(host), port, Transport.UDP);
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject localDescription() {
        JsonArray candidates = new JsonArray();
        for (LocalCandidate candidate : component.getLocalCandidates()) {
            if (candidate.getTransportAddress().getAddress() != null
                    && candidate.getTransportAddress().getAddress().isLinkLocalAddress()) {
                // link local IPv6 is useless once the two peers are not on the same switch
                continue;
            }
            JsonObject o = Json.obj();
            o.addProperty("ip", candidate.getTransportAddress().getHostAddress());
            o.addProperty("port", candidate.getTransportAddress().getPort());
            o.addProperty("type", candidate.getType().toString());
            o.addProperty("prio", candidate.getPriority());
            o.addProperty("fnd", candidate.getFoundation());
            candidates.add(o);
        }
        JsonObject o = Json.obj();
        o.addProperty("ufrag", agent.getLocalUfrag());
        o.addProperty("pwd", agent.getLocalPassword());
        o.add("cands", candidates);
        return o;
    }

    public void acceptRemote(JsonObject remote) throws Exception {
        stream.setRemoteUfrag(Json.str(remote, "ufrag"));
        stream.setRemotePassword(Json.str(remote, "pwd"));
        JsonElement list = remote.get("cands");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : list.getAsJsonArray()) {
                JsonObject c = element.getAsJsonObject();
                TransportAddress address = new TransportAddress(
                        InetAddress.getByName(Json.str(c, "ip")), (int) Json.num(c, "port"), Transport.UDP);
                RemoteCandidate candidate = new RemoteCandidate(address, component,
                        CandidateType.parse(Json.str(c, "type")), Json.str(c, "fnd"), Json.num(c, "prio"), null);
                component.addRemoteCandidate(candidate);
            }
        }
        agent.startConnectivityEstablishment();
    }

    public boolean awaitConnected(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !closed) {
            IceProcessingState state = agent.getState();
            if (state == IceProcessingState.COMPLETED) {
                return true;
            }
            if (state == IceProcessingState.FAILED || state == IceProcessingState.TERMINATED) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return agent.getState() == IceProcessingState.COMPLETED;
    }

    /** Human readable description of the path that was actually selected. */
    public record PairInfo(String kind, String local, String remote) {
        public static final PairInfo EMPTY = new PairInfo("", "", "");
    }

    public PairInfo pairInfo() {
        try {
            CandidatePair pair = component.getSelectedPair();
            if (pair == null) {
                return PairInfo.EMPTY;
            }
            LocalCandidate local = pair.getLocalCandidate();
            RemoteCandidate remote = pair.getRemoteCandidate();
            String localAddress = local.getTransportAddress().getHostAddress() + ":"
                    + local.getTransportAddress().getPort();
            String remoteAddress = remote.getTransportAddress().getHostAddress() + ":"
                    + remote.getTransportAddress().getPort();
            return new PairInfo(kindOf(local, remote), localAddress, remoteAddress);
        } catch (Exception e) {
            return PairInfo.EMPTY;
        }
    }

    private static String kindOf(LocalCandidate local, RemoteCandidate remote) {
        java.net.InetAddress localAddress = local.getTransportAddress().getAddress();
        java.net.InetAddress remoteAddress = remote.getTransportAddress().getAddress();
        if (local.getType() == CandidateType.RELAYED_CANDIDATE || remote.getType() == CandidateType.RELAYED_CANDIDATE) {
            return "relay";
        }
        if (localAddress != null && remoteAddress != null
                && (localAddress.isLoopbackAddress() || localAddress.isSiteLocalAddress()
                    || localAddress.isLinkLocalAddress())
                && (remoteAddress.isLoopbackAddress() || remoteAddress.isSiteLocalAddress()
                    || remoteAddress.isLinkLocalAddress())) {
            return "lan";
        }
        if (localAddress instanceof java.net.Inet6Address || remoteAddress instanceof java.net.Inet6Address) {
            return "ipv6";
        }
        return "punch";
    }

    public boolean isRelayed() {
        Candidate<?> local = component.getSelectedPair() == null ? null
                : component.getSelectedPair().getLocalCandidate();
        return local != null && local.getType() == CandidateType.RELAYED_CANDIDATE;
    }

    public void send(byte[] data) {
        if (closed) {
            return;
        }
        try {
            component.send(data, 0, data.length);
        } catch (Exception ignored) {
        }
    }

    /** Blocks until one datagram arrives, or returns null once the link is closed. */
    public byte[] receive() {
        DatagramSocket socket = component.getSocket();
        if (socket == null) {
            return null;
        }
        DatagramPacket packet = new DatagramPacket(new byte[MAX_DATAGRAM], MAX_DATAGRAM);
        try {
            socket.receive(packet);
        } catch (Exception e) {
            return null;
        }
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    @Override
    public void close() {
        closed = true;
        try {
            DatagramSocket socket = component.getSocket();
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        try {
            agent.free();
        } catch (Exception ignored) {
        }
    }
}
