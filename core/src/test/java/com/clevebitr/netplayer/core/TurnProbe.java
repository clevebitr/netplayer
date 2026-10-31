package com.clevebitr.netplayer.core;

import java.net.InetSocketAddress;

/**
 * Manual probe: tries one TURN server and prints what it answered.
 *
 * <pre>
 * java -cp core/build/classes/java/main core/.../TurnProbe.class ...
 * </pre>
 *
 * Usage: {@code TurnProbe <turn:host:port> [username] [password]}
 */
public final class TurnProbe {
    private TurnProbe() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: TurnProbe turn:host:port [user] [pass]");
            return;
        }
        String spec = args[0];
        String user = args.length > 1 ? args[1] : "";
        String pass = args.length > 2 ? args[2] : "";
        InetSocketAddress server = TurnClient.parseServer(spec);
        if (server == null) {
            System.out.println("cannot parse " + spec);
            return;
        }
        long started = System.currentTimeMillis();
        try (TurnClient client = new TurnClient(server, user, pass)) {
            TurnClient.Allocation allocation = client.allocate(600);
            System.out.printf("OK   %-42s relay=%s mapped=%s lifetime=%ss (%dms)%n", spec,
                    allocation.relayed(), allocation.mapped(), allocation.lifetimeSeconds(),
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            System.out.printf("FAIL %-42s %s (%dms)%n", spec, e.getMessage(),
                    System.currentTimeMillis() - started);
        }
    }
}
