package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.Friend;
import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
            }).bounds(left, 56, 150, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("netplayer.open.stop"), b -> {
                client.closeWorld();
                minecraft.setScreen(parent);
            }).bounds(left + 160, 56, 150, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("netplayer.button.direct_offer"), b -> {
                client.generateDirectOffer();
                note = Component.translatable("netplayer.open.generating");
            }).bounds(left, 82, 150, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.open.direct_tip")))
                    .build());

            addRenderableWidget(Button.builder(Component.translatable("netplayer.button.copy_direct"), b -> {
                String code = client.lastDirectOffer();
                if (code.isEmpty()) {
                    note = Component.translatable("netplayer.open.no_direct");
                } else {
                    minecraft.keyboardHandler.setClipboard(code);
                    note = Component.translatable("netplayer.add.copied");
                }
            }).bounds(left + 160, 82, 150, 20).build());
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

            addRenderableWidget(Button.builder(premiumLabel(), b -> {
                client.setVerifyPremium(!client.verifyPremium());
                b.setMessage(premiumLabel());
            }).bounds(left, 94, 310, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.open.premium_tip")))
                    .build());

            List<Friend> friends = client.friends();
            int y = 120;
            for (int i = 0; i < Math.min(friends.size(), 4); i++) {
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

    private Component premiumLabel() {
        return Component.translatable("netplayer.open.premium",
                Component.translatable(client.verifyPremium() ? "netplayer.value.on" : "netplayer.value.off"));
    }

    private Component rowLabel(Friend friend) {
        return Component.literal((allowed.contains(friend.profile.id) ? "[x] " : "[ ] ") + friend.label());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (client.hosting()) {
            graphics.drawCenteredString(font, Component.translatable("netplayer.open.is_open",
                    client.room().world()), width / 2, 36, 0x55FF55);
            graphics.drawString(font, Component.translatable("netplayer.open.direct_line1"), left, 112, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("netplayer.open.direct_line2"), left, 124, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("netplayer.open.direct_line3"), left, 136, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable(client.room().premium()
                    ? "netplayer.open.premium_on" : "netplayer.open.premium_off"), left, 154, 0x80C0FF, false);
        } else if (!note.getString().isEmpty()) {
            graphics.drawCenteredString(font, note, width / 2, 40, 0xFFAA00);
        }
        if (!client.localPremiumCapable()) {
            graphics.drawString(font, Component.translatable("netplayer.open.local_not_premium"),
                    left, 218, 0xFF8855, false);
        }
        if (!note.getString().isEmpty() && client.hosting()) {
            graphics.drawCenteredString(font, note, width / 2, height - 70, 0xFFAA00);
        }
        if (!client.connected()) {
            graphics.drawCenteredString(font, Component.translatable("netplayer.status.direct_mode"),
                    width / 2, height - 40, 0xFFCC00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
