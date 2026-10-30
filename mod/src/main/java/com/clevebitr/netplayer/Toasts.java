package com.clevebitr.netplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Thin wrapper over the vanilla toast stack so notifications look like the rest of the game. */
public final class Toasts {
    private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId(4000L);

    private Toasts() {
    }

    public static void info(Component title, Component subtitle) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.getToastManager(), ID, title, subtitle));
    }

    public static void info(String titleKey, String subtitleKey, Object... args) {
        info(Component.translatable(titleKey), Component.translatable(subtitleKey, args));
    }
}
