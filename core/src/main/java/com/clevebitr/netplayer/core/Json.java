package com.clevebitr.netplayer.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonObject parse(String text) {
        JsonElement e = GSON.fromJson(text, JsonElement.class);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    public static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    public static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    public static JsonObject sub(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /**
     * Deterministic serialization: object keys sorted, no whitespace. Signatures are computed over
     * this form so that a re-serialized payload still verifies and no field can be swapped out
     * after the fact (plain {@link #GSON} keeps insertion order, which is not a contract).
     */
    public static String canonical(JsonElement element) {
        StringBuilder sb = new StringBuilder();
        write(element, sb);
        return sb.toString();
    }

    /** Copy of {@code o} without {@code key}, ready to be signed (a signature cannot cover itself). */
    public static JsonObject without(JsonObject o, String key) {
        JsonObject copy = new JsonObject();
        for (var entry : o.entrySet()) {
            if (!entry.getKey().equals(key)) {
                copy.add(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static void write(JsonElement element, StringBuilder sb) {
        if (element == null || element.isJsonNull()) {
            sb.append("null");
            return;
        }
        if (element.isJsonObject()) {
            sb.append('{');
            java.util.List<String> keys = new java.util.ArrayList<>(element.getAsJsonObject().keySet());
            java.util.Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(GSON.toJson(key)).append(':');
                write(element.getAsJsonObject().get(key), sb);
            }
            sb.append('}');
            return;
        }
        if (element.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(child, sb);
            }
            sb.append(']');
            return;
        }
        sb.append(GSON.toJson(element));
    }
}
