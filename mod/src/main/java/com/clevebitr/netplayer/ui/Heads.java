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

    private static PlayerSkin skin(String name, String uuid) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ResolvableProfile profile;
            if (uuid != null && !uuid.isEmpty()) {
                profile = ResolvableProfile.createResolved(new GameProfile(UUID.fromString(uuid), name));
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
