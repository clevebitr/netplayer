package com.clevebitr.netplayer;

import com.clevebitr.netplayer.core.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Config {
    /** Set to e.g. {@code wss://netplayer-signal.example.workers.dev/} to use a WebSocket server. */
    public String signalingUrl = "";
    public String signalingHost = "127.0.0.1";
    public int signalingPort = 25566;
    public List<String> stunServers = new ArrayList<>(
            List.of("stun.miwifi.com:3478", "stun.chat.bilibili.com:3478"));
    public boolean iceDebug = false;
    public boolean verifyPremium = true;

    public static Config load(Path file) {
        if (Files.isRegularFile(file)) {
            try {
                Config config = Json.GSON.fromJson(Files.readString(file), Config.class);
                if (config != null) {
                    if (config.stunServers == null || config.stunServers.isEmpty()) {
                        config.stunServers = new ArrayList<>(List.of("stun.miwifi.com:3478"));
                    }                    if (config.signalingHost == null || config.signalingHost.isBlank()) {
                        config.signalingHost = "127.0.0.1";
                    }
                    if (config.signalingUrl == null) {
                        config.signalingUrl = "";
                    }
                    return config;
                }
            } catch (Exception ignored) {
            }
        }
        Config config = new Config();
        config.save(file);
        return config;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.GSON.toJson(this));
        } catch (Exception ignored) {
        }
    }

    /** Human readable endpoint, used in logs and in the "not connected" hint. */
    public String signalingTarget() {
        if (signalingUrl != null && !signalingUrl.isBlank()) {
            return signalingUrl.trim();
        }
        return signalingHost + ":" + signalingPort;
    }
}
