package com.clevebitr.netplayer;

import com.clevebitr.netplayer.core.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Config {
    public String signalingHost = "127.0.0.1";
    public int signalingPort = 25566;
    public List<String> stunServers = new ArrayList<>(
            List.of("stun.miwifi.com:3478", "stun.chat.bilibili.com:3478"));

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
}
