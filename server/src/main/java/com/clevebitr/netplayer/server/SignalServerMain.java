package com.clevebitr.netplayer.server;

public final class SignalServerMain {
    private SignalServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = 25566;
        for (int i = 0; i < args.length; i++) {
            if (("--port".equals(args[i]) || "-p".equals(args[i])) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        SignalServer server = new SignalServer(port);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
