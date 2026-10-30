package com.clevebitr.netplayer;

import com.clevebitr.netplayer.ui.FriendListScreen;
import com.clevebitr.netplayer.ui.OpenWorldScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NetPlayer.MOD_ID, "main"));

    public static final KeyMapping OPEN_FRIENDS = new KeyMapping("key.netplayer.friends",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY);

    private ClientEvents() {
    }

    public static void registerMod(IEventBus modBus) {
        modBus.addListener(ClientEvents::onRegisterKeys);
    }

    public static void registerGame() {
        NeoForge.EVENT_BUS.addListener(ClientEvents::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_FRIENDS);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof TitleScreen) {
            event.addListener(Button.builder(Component.translatable("netplayer.button.friends"),
                            b -> Minecraft.getInstance().setScreen(new FriendListScreen(screen)))
                    .bounds(6, screen.height - 26, 98, 20).build());
        } else if (screen instanceof PauseScreen) {
            int y = screen.height - 26;
            event.addListener(Button.builder(Component.translatable("netplayer.button.friends"),
                            b -> Minecraft.getInstance().setScreen(new FriendListScreen(screen)))
                    .bounds(6, y, 98, 20).build());
            event.addListener(Button.builder(Component.translatable("netplayer.button.open_world"),
                            b -> Minecraft.getInstance().setScreen(new OpenWorldScreen(screen)))
                    .bounds(6, y - 24, 98, 20).build());
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        NetPlayerClient.get().tick();
        if (OPEN_FRIENDS.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new FriendListScreen(mc.screen));
        }
    }
}
