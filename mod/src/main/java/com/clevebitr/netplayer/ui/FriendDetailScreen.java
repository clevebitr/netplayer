package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.Friend;
import com.clevebitr.netplayer.NetPlayerClient;
import com.clevebitr.netplayer.Presence;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Everything about one friend: head, identity, presence, live link state and note. */
public final class FriendDetailScreen extends Screen {
    private final Screen parent;
    private final String friendId;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox noteBox;

    public FriendDetailScreen(Screen parent, String friendId) {
        super(Component.translatable("netplayer.detail.title"));
        this.parent = parent;
        this.friendId = friendId;
    }

    private Friend friend() {
        return client.friendMap().get(friendId);
    }

    @Override
    protected void init() {
        client.init();
        Friend friend = friend();
        if (friend == null) {
            minecraft.setScreen(parent);
            return;
        }
        int left = width / 2 - 155;

        noteBox = new EditBox(font, left, 144, 230, 18, Component.translatable("netplayer.detail.note_hint"));
        noteBox.setMaxLength(48);
        noteBox.setValue(friend.note == null ? "" : friend.note);
        addRenderableWidget(noteBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.detail.save_note"), b -> {
            client.setNote(friendId, noteBox.getValue());
            noteBox.setValue(client.friendMap().get(friendId).note);
        }).bounds(left + 234, 144, 76, 18).build());

        Button join = Button.builder(Component.translatable("netplayer.detail.join"),
                        b -> client.requestJoin(friendId))
                .bounds(left, 172, 150, 20)
                .tooltip(Tooltip.create(Component.translatable("netplayer.friends.join_tip")))
                .build();
        join.active = client.canJoin(friendId);
        if (client.insideWorldOf(friendId)) {
            join.setTooltip(Tooltip.create(Component.translatable("netplayer.detail.already_inside")));
        } else if (client.joiningFriend(friendId)) {
            join.setTooltip(Tooltip.create(Component.translatable("netplayer.error.already_joining")));
        }
        addRenderableWidget(join);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.detail.copy_key"), b -> {
            minecraft.keyboardHandler.setClipboard(com.clevebitr.netplayer.core.Invite.friendKey(friend.profile));
        }).bounds(left + 160, 172, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.detail.remove"), b -> {
            client.removeFriend(friendId);
            minecraft.setScreen(parent);
        }).bounds(left, 196, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left + 160, 196, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Friend friend = friend();
        if (friend == null) {
            return;
        }
        Presence presence = client.presence(friendId);
        NetPlayerClient.LinkInfo link = client.linkInfo(friendId);
        int left = width / 2 - 155;

        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        Heads.draw(graphics, friend.mcName, friend.mcUuid, left, 28, 32);

        graphics.drawString(font, Component.literal(friend.label()), left + 38, 30, 0xFFFFFFFF, false);
        if (!friend.mcName.isEmpty()) {
            graphics.drawString(font, Component.literal(friend.mcName), left + 38, 42, 0xFFA0A0A0, false);
        }
        graphics.drawString(font, Component.translatable("netplayer.detail.id", friend.profile.id),
                left + 38, 54, 0xFF808080, false);

        graphics.drawString(font, Component.translatable("netplayer.detail.status"), left, 68, 0xFFFFFF55, false);
        graphics.drawString(font, presenceText(presence), left, 80, 0xFFFFFFFF, false);

        graphics.drawString(font, Component.translatable("netplayer.detail.link"), left, 94, 0xFFFFFF55, false);
        graphics.drawString(font, linkText(link), left, 106, link.active() ? 0xFF55FF55 : 0xFF909090, false);
        graphics.drawString(font, Component.translatable("netplayer.detail.note"), left, 132, 0xFFFFFF55, false);
    }

    private Component presenceText(Presence presence) {
        // The local join state wins over whatever the relay says: it is the only thing that knows
        // whether we are actually inside, still connecting, or freshly failed.
        String stateKey = client.joinStateKey(friendId);
        if (stateKey != null) {
            return Component.translatable(stateKey);
        }
        if (!presence.online()) {
            return Component.translatable("netplayer.status.offline");
        }
        if (presence.hosting()) {
            return Component.translatable(presence.openTo(client.me().id)
                    ? "netplayer.status.hosting_open" : "netplayer.status.hosting", presence.world);
        }
        return switch (presence.state) {
            case "playing", "hosting" -> Component.translatable("netplayer.status.playing", presence.detail);
            case "server" -> Component.translatable("netplayer.status.server", presence.detail);
            default -> Component.translatable("netplayer.status.online");
        };
    }

    private Component linkText(NetPlayerClient.LinkInfo link) {
        if (!link.active()) {
            return Component.translatable("netplayer.link.none");
        }
        Component kind = Component.translatable(switch (link.kind()) {
            case "lan" -> "netplayer.link.lan";
            case "ipv6" -> "netplayer.link.ipv6";
            case "relay" -> "netplayer.link.relay";
            case "punch" -> "netplayer.link.punch";
            default -> "netplayer.link.unknown";
        });
        if (link.rttMillis() < 0) {
            return kind;
        }
        return Component.translatable("netplayer.link.with_rtt", kind, link.rttMillis());
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
