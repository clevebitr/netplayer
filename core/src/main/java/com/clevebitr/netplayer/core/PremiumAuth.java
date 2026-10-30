package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Mojang session server checks, the same two calls a real online-mode server does:
 * the guest proves it owns the account with {@link #join}, the host confirms it with
 * {@link #hasJoined}. Only the account ownership is checked, the tunnel stays ours.
 */
public final class PremiumAuth {
    private static final String SESSION = "https://sessionserver.mojang.com/session/minecraft/";
    private static volatile String proxyHost = "";
    private static volatile int proxyPort;
    private static volatile HttpClient http;

    /** Routes the session server calls through an http proxy when the game needs one. */
    public static void setProxy(String host, int port) {
        proxyHost = host == null ? "" : host.trim();
        proxyPort = port;
        http = null;
    }

    private static HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (!proxyHost.isEmpty() && proxyPort > 0) {
                builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxyHost, proxyPort)));
            }
            client = builder.build();
            http = client;
        }
        return client;
    }

    private PremiumAuth() {
    }

    /** Asks Mojang to record that this account wants to join {@code serverId}. */
    public static boolean join(String accessToken, String profileId, String serverId) {
        if (accessToken == null || accessToken.isBlank() || "0".equals(accessToken)) {
            return false;
        }
        try {
            JsonObject body = Json.obj();
            body.addProperty("accessToken", accessToken);
            body.addProperty("selectedProfile", profileId.replace("-", ""));
            body.addProperty("serverId", serverId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(SESSION + "join"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 204 || response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the verified profile, or null when that account never joined this serverId. */
    public static JsonObject hasJoined(String name, String serverId) {
        if (name == null || name.isBlank() || serverId == null || serverId.isBlank()) {
            return null;
        }
        try {
            String url = SESSION + "hasJoined?username=" + java.net.URLEncoder.encode(name, "UTF-8")
                    + "&serverId=" + java.net.URLEncoder.encode(serverId, "UTF-8");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return null;
            }
            JsonObject profile = Json.parse(response.body());
            String id = Json.str(profile, "id");
            return id.isEmpty() ? null : profile;
        } catch (Exception e) {
            return null;
        }
    }

    public static String newServerId() {
        return Bytes.hex(Bytes.random(20));
    }
}
