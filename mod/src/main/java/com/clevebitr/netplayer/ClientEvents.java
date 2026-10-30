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
            Button multiplayer = findButton(screen, Component.translatable("menu.multiplayer"));
            if (multiplayer != null) {
                insertRow(event, screen, multiplayer.getY(), multiplayer.getX(), multiplayer.getWidth(),
                        List.of(friendsButton(screen)), 1);
            }
        } else if (screen instanceof PauseScreen) {
            Button left = findButton(screen, Component.translatable("menu.options"));
            Button right = findButton(screen, Component.translatable("menu.playerReporting"));
            if (right == null) {
                right = findButton(screen, Component.translatable("menu.shareToLan"));
            }
            if (left == null) {
                left = right;
            }
            if (left == null) {
                return;
            }
            List<Button> buttons = new ArrayList<>();
            buttons.add(friendsButton(screen));
            // Only my own world can be shared, never somebody else's world.
            if (Minecraft.getInstance().hasSingleplayerServer()) {
                buttons.add(openWorldButton(screen));
            }
            // Align with the full width rows (return to game / mods) so the row lines up with vanilla.
            Button full = findButton(screen, Component.translatable("menu.returnToGame"));
            int rowLeft = full != null ? full.getX() : left.getX();
            int rowWidth = full != null ? full.getWidth() : left.getWidth() * 2 + 8;
            insertRow(event, screen, left.getY(), rowLeft, rowWidth, buttons, 2);
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

    /**
     * Inserts one extra row right below {@code rowTop}, in the same column layout as vanilla
     * ({@code columns} cells), and pushes the rows below it down so nothing overlaps.
     */
    private static void insertRow(ScreenEvent.Init.Post event, Screen screen, int rowTop, int rowLeft,
                                  int rowWidth, List<Button> buttons, int columns) {
        if (buttons.isEmpty()) {
            return;
        }
        int top = rowTop + ROW;
        int bottomLimit = screen.height - 40;
        for (GuiEventListener listener : new ArrayList<>(screen.children())) {
            if (listener instanceof AbstractWidget widget
                    && widget.getY() >= top && widget.getY() <= bottomLimit) {
                widget.setY(widget.getY() + ROW);
            }
        }
        // Vanilla GridLayout cells are 4px padded on both sides: a 98 wide button sits in a 106 cell,
        // and a full width row of 204 spans 212. Match that exactly so the columns line up.
        int cell = columns <= 1 ? rowWidth : (rowWidth + 8) / columns;
        boolean fullWidth = columns <= 1 || buttons.size() == 1;
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            button.setX(fullWidth ? rowLeft : rowLeft + i * cell);
            button.setY(top);
            button.setWidth(fullWidth ? rowWidth : Math.max(40, cell - 8));
            button.setHeight(20);
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
