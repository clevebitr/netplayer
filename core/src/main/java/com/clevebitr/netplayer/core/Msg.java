package com.clevebitr.netplayer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** One line of the signaling protocol: {"t":"type","d":{...}}. */
public final class Msg {
    public final String type;
    public final JsonObject data;

    public Msg(String type, JsonObject data) {
        this.type = type;
        this.data = data == null ? Json.obj() : data;
    }

    public static Msg of(String type) {
        return new Msg(type, Json.obj());
    }

    public static Msg of(String type, JsonObject data) {
        return new Msg(type, data);
    }

    public String toLine() {
        JsonObject root = Json.obj();
        root.addProperty("t", type);
        root.add("d", data);
        return Json.GSON.toJson(root);
    }

    public static Msg parse(String line) {
        JsonObject root = Json.parse(line);
        return new Msg(Json.str(root, "t"), Json.sub(root, "d"));
    }

    public Msg put(String key, String value) {
        data.addProperty(key, value);
        return this;
    }

    public Msg put(String key, boolean value) {
        data.addProperty(key, value);
        return this;
    }

    public Msg put(String key, long value) {
        data.addProperty(key, value);
        return this;
    }

    public Msg put(String key, JsonObject value) {
        data.add(key, value);
        return this;
    }

    public Msg put(String key, List<String> values) {
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        data.add(key, arr);
        return this;
    }

    public String str(String key) {
        return Json.str(data, key);
    }

    public long num(String key) {
        return Json.num(data, key);
    }

    public boolean bool(String key) {
        return Json.bool(data, key);
    }

    public JsonObject obj(String key) {
        return Json.sub(data, key);
    }

    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = data.get(key);
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        }
        return out;
    }

    @Override
    public String toString() {
        return type + data;
    }
}
