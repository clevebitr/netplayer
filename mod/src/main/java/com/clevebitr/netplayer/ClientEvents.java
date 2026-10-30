package com.clevebitr.netplayer;

import com.clevebitr.netplayer.ui.FriendListScreen;
import com.clevebitr.netplayer.ui.OpenWorldScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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

import java.util.ArrayList;
import java.util.List;

public final class ClientEvents {
    private static final int ROW = 24;

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
            Button anchor = findButton(screen, Component.translatable("menu.multiplayer"));
            insertBelow(event, screen, anchor, List.of(friendsButton(screen)), 200);
        } else if (screen instanceof PauseScreen) {
            Button anchor = findButton(screen, Component.translatable("menu.playerReporting"));
            if (anchor == null) {
                anchor = findButton(screen, Component.translatable("menu.shareToLan"));
            }
            if (anchor == null) {
                anchor = findButton(screen, Component.translatable("menu.options"));
            }
            List<Button> buttons = new ArrayList<>();
            buttons.add(friendsButton(screen));
            // Only my own world can be shared, never somebody else's world.
            if (Minecraft.getInstance().hasSingleplayerServer()) {
                buttons.add(openWorldButton(screen));
            }
            insertBelow(event, screen, anchor, buttons, 204);
        }
    }

    private static Button friendsButton(Screen parent) {
        return Button.builder(Component.translatable("netplayer.button.friends"),
                        b -> Minecraft.getInstance().setScreen(new FriendListScreen(parent)))
                .tooltip(Tooltip.create(Component.translatable("netplayer.title.tooltip")))
                .build();
    }

    private static Button openWorldButton(Screen parent) {
        return Button.builder(Component.translatable("netplayer.button.open_world"),
                        b -> Minecraft.getInstance().setScreen(new OpenWorldScreen(parent)))
                .tooltip(Tooltip.create(Component.translatable("netplayer.open.tooltip")))
                .build();
    }

    /** Adds the buttons as a new vertical row right below {@code anchor}, pushing later rows down. */
    private static void insertBelow(ScreenEvent.Init.Post event, Screen screen, Button anchor, List<Button> buttons,
                                    int rowWidth) {
        if (buttons.isEmpty()) {
            return;
        }
        if (anchor == null) {
            int x = 4;
            int y = screen.height - 26 - (buttons.size() - 1) * ROW;
            for (int i = 0; i < buttons.size(); i++) {
                Button button = buttons.get(i);
                button.setX(x);
                button.setY(y + i * ROW);
                button.setWidth(98);
                button.setHeight(20);
                event.addListener(button);
            }
            return;
        }
        int top = anchor.getY() + ROW;
        int shift = ROW * buttons.size();
        int bottomLimit = screen.height - 40;
        for (GuiEventListener listener : new ArrayList<>(screen.children())) {
            if (listener instanceof AbstractWidget widget && widget != anchor
                    && widget.getY() >= top && widget.getY() <= bottomLimit) {
                widget.setY(widget.getY() + shift);
            }
        }
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            button.setX(anchor.getX());
            button.setY(top + i * ROW);
            button.setWidth(rowWidth);
            button.setHeight(anchor.getHeight());
            event.addListener(button);
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
