package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.Friend;
import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OpenWorldScreen extends Screen {
    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private final Set<String> allowed = new HashSet<>();
    private GameType gameType = GameType.SURVIVAL;
    private boolean cheats;
    private boolean everyone = true;
    private Component note = Component.empty();

    public OpenWorldScreen(Screen parent) {
        super(Component.translatable("netplayer.open.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        client.init();
        int left = width / 2 - 155;

        if (client.hosting()) {
            addRenderableWidget(Button.builder(Component.translatable("netplayer.open.copy"), b -> {
                minecraft.keyboardHandler.setClipboard(client.myRoomKey());
                note = Component.translatable("netplayer.add.copied");
            }).bounds(left, 60, 150, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("netplayer.open.stop"), b -> {
                client.closeWorld();
                minecraft.setScreen(parent);
            }).bounds(left + 160, 60, 150, 20).build());
        } else if (minecraft.getSingleplayerServer() == null) {
            note = Component.translatable("netplayer.error.no_world");
        } else {
            addRenderableWidget(Button.builder(modeLabel(), b -> {
                gameType = gameType == GameType.SURVIVAL ? GameType.CREATIVE : GameType.SURVIVAL;
                b.setMessage(modeLabel());
            }).bounds(left, 46, 150, 20).build());

            addRenderableWidget(Button.builder(cheatsLabel(), b -> {
                cheats = !cheats;
                b.setMessage(cheatsLabel());
            }).bounds(left + 160, 46, 150, 20).build());

            addRenderableWidget(Button.builder(allLabel(), b -> {
                everyone = !everyone;
                b.setMessage(allLabel());
            }).bounds(left, 72, 310, 20).build());

            List<Friend> friends = client.friends();
            int y = 98;
            for (int i = 0; i < Math.min(friends.size(), 5); i++) {
                Friend friend = friends.get(i);
                String id = friend.profile.id;
                Button button = Button.builder(rowLabel(friend), b -> {
                    if (!allowed.remove(id)) {
                        allowed.add(id);
                    }
                    b.setMessage(rowLabel(friend));
                }).bounds(left, y, 310, 20).build();
                button.active = !everyone;
                addRenderableWidget(button);
                y += 22;
            }

            addRenderableWidget(Button.builder(Component.translatable("netplayer.open.start"), b -> {
                client.openWorld(gameType, cheats, everyone, allowed);
                minecraft.setScreen(parent);
            }).bounds(left, height - 52, 310, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left, height - 28, 310, 20).build());
    }

    private Component modeLabel() {
        return Component.translatable("netplayer.open.mode",
                Component.translatable(gameType == GameType.SURVIVAL ? "netplayer.mode.survival" : "netplayer.mode.creative"));
    }

    private Component cheatsLabel() {
        return Component.translatable("netplayer.open.cheats",
                Component.translatable(cheats ? "netplayer.value.on" : "netplayer.value.off"));
    }

    private Component allLabel() {
        return Component.translatable("netplayer.open.all_friends",
                Component.translatable(everyone ? "netplayer.value.on" : "netplayer.value.off"));
    }

    private Component rowLabel(Friend friend) {
        return Component.literal((allowed.contains(friend.profile.id) ? "[x] " : "[ ] ") + friend.label());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        if (!note.getString().isEmpty()) {
            graphics.drawCenteredString(font, note, width / 2, height - 70, 0xFFAA00);
        }
        if (client.hosting()) {
            graphics.drawCenteredString(font, Component.translatable("netplayer.open.is_open",
                    client.room().world()), width / 2, 40, 0x55FF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
