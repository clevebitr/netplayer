package com.clevebitr.netplayer;

import com.clevebitr.netplayer.ui.FriendListScreen;
import com.clevebitr.netplayer.ui.OpenWorldScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY);

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
            Button multiplayer = findButton(screen, Component.translatable("menu.multiplayer"));
            int y = multiplayer != null ? multiplayer.getY() : screen.height / 4 + 56;
            int x = screen.width / 2 + 102;
            if (x + 98 > screen.width - 4) {
                x = 4;
                y = screen.height - 26;
            }
            event.addListener(Button.builder(Component.translatable("netplayer.button.friends"),
                            b -> Minecraft.getInstance().setScreen(new FriendListScreen(screen)))
                    .bounds(x, y, 98, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.title.tooltip")))
                    .build());
        } else if (screen instanceof PauseScreen) {
            Button anchor = findButton(screen, Component.translatable("menu.shareToLan"));
            if (anchor == null) {
                anchor = findButton(screen, Component.translatable("menu.playerReporting"));
            }
            if (anchor == null) {
                anchor = findButton(screen, Component.translatable("menu.options"));
            }
            int y = anchor != null ? anchor.getY() : screen.height / 2;
            int x = screen.width / 2 + 112;
            if (x + 98 > screen.width - 4) {
                x = 4;
                y = screen.height - 76;
            }
            event.addListener(Button.builder(Component.translatable("netplayer.button.friends"),
                            b -> Minecraft.getInstance().setScreen(new FriendListScreen(screen)))
                    .bounds(x, y, 98, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.title.tooltip")))
                    .build());
            event.addListener(Button.builder(Component.translatable("netplayer.button.open_world"),
                            b -> Minecraft.getInstance().setScreen(new OpenWorldScreen(screen)))
                    .bounds(x, y + 24, 98, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.open.tooltip")))
                    .build());
        }
    }

    private static Button findButton(Screen screen, Component message) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        NetPlayerClient.get().tick();
        if (OPEN_FRIENDS.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new FriendListScreen(mc.screen));
        }
    }
}
