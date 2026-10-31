package com.clevebitr.netplayer.ui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

/** Draws a player head for a friend, falls back to the default skin of that uuid. */
public final class Heads {
    private Heads() {
    }

    public static void draw(GuiGraphics graphics, String name, String uuid, int x, int y, int size) {
        PlayerSkin skin = skin(name, uuid);
        if (skin != null) {
            PlayerFaceRenderer.draw(graphics, skin, x, y, size);
        }
    }

    /** Accepts both {@code 8-4-4-4-12} and the bare 32 hex form. */
    private static String dashed(String uuid) {
        String hex = uuid.replace("-", "");
        if (hex.length() != 32) {
            return uuid;
        }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    private static PlayerSkin skin(String name, String uuid) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ResolvableProfile profile;
            if (uuid != null && !uuid.isEmpty()) {
                profile = ResolvableProfile.createResolved(new GameProfile(UUID.fromString(dashed(uuid)), name));
            } else if (name != null && !name.isEmpty()) {
                profile = ResolvableProfile.createUnresolved(name);
            } else {
                return null;
            }
            return mc.playerSkinRenderCache().getOrDefault(profile).playerSkin();
        } catch (Exception e) {
            return null;
        }
    }
}
