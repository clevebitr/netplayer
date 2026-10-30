package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.Friend;
import com.clevebitr.netplayer.NetPlayerClient;
import com.clevebitr.netplayer.Presence;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class FriendListScreen extends Screen {
    private static final int PER_PAGE = 6;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private final List<AbstractWidget> rows = new ArrayList<>();
    private EditBox search;
    private int page;
    private String filter = "";

    public FriendListScreen(Screen parent) {
        super(Component.translatable("netplayer.friends.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        client.init();
        int left = width / 2 - 155;

        search = new EditBox(font, left, 32, 200, 18, Component.translatable("netplayer.friends.search"));
        search.setMaxLength(64);
        search.setValue(filter);
        search.setResponder(value -> {
            filter = value;
            page = 0;
            refreshRows();
        });
        addRenderableWidget(search);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.add_friend"),
                        b -> minecraft.setScreen(new AddFriendScreen(this)))
                .bounds(left + 204, 32, 106, 18)
                .tooltip(Tooltip.create(Component.translatable("netplayer.friends.add_tip")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> {
                            page = Math.max(0, page - 1);
                            refreshRows();
                        })
                .bounds(left, height - 28, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> {
                            page++;
                            refreshRows();
                        })
                .bounds(left + 28, height - 28, 24, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left + 160, height - 28, 150, 20).build());

        refreshRows();
    }

    private void refreshRows() {
        for (AbstractWidget widget : rows) {
            removeWidget(widget);
        }
        rows.clear();

        int left = width / 2 - 155;
        int y = 58;
        int limit = Math.max(1, (height - 58 - 34) / ROW_HEIGHT);
        int used = 0;

        for (NetPlayerClient.Pending pending : client.pendingJoins()) {
            if (used >= 2 || used >= limit) {
                break;
            }
            used++;
            Component label = Component.translatable("netplayer.friends.join_request", pending.guest().shortName());
            rows.add(addRow(Button.builder(label, b -> {
                client.answerJoin(pending, true);
                refreshRows();
            }).bounds(left, y, 200, 20).build()));
            rows.add(addRow(Button.builder(Component.translatable("netplayer.button.accept"), b -> {
                client.answerJoin(pending, true);
                refreshRows();
            }).bounds(left + 204, y, 50, 20).build()));
            rows.add(addRow(Button.builder(Component.translatable("netplayer.button.decline"), b -> {
                client.answerJoin(pending, false);
                refreshRows();
            }).bounds(left + 258, y, 52, 20).build()));
            y += ROW_HEIGHT;
        }

        List<Friend> visible = visibleFriends();
        for (int i = 0; i < PER_PAGE && used < limit; i++) {
            int index = page * PER_PAGE + i;
            if (index >= visible.size()) {
                break;
            }
            used++;
            Friend friend = visible.get(index);
            Presence presence = client.presence(friend.profile.id);
            Component label = Component.literal(friend.label() + "  ").append(status(friend.profile.id, presence));
            Button join = Button.builder(label, b -> client.requestJoin(friend.profile.id))
                    .bounds(left, y, 268, 20).build();
            join.active = presence.openTo(client.me().id);
            join.setTooltip(Tooltip.create(Component.translatable(presence.openTo(client.me().id)
                    ? "netplayer.friends.join_tip" : "netplayer.friends.not_open_tip")));
            rows.add(addRow(join));

            String id = friend.profile.id;
            rows.add(addRow(Button.builder(Component.literal("\u2715"), b -> {
                client.removeFriend(id);
                refreshRows();
            }).bounds(left + 272, y, 38, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.friends.remove_tip")))
                    .build()));
            y += ROW_HEIGHT;
        }

        if (visible.isEmpty() && used == 0) {
            rows.add(addRow(Button.builder(Component.translatable("netplayer.friends.empty"),
                            b -> minecraft.setScreen(new AddFriendScreen(this)))
                    .bounds(left, y, 310, 20).build()));
        }
    }

    private AbstractWidget addRow(AbstractWidget widget) {
        addRenderableWidget(widget);
        return widget;
    }

    private List<Friend> visibleFriends() {
        List<Friend> all = client.friends();
        if (filter == null || filter.isBlank()) {
            return all;
        }
        String needle = filter.toLowerCase();
        List<Friend> out = new ArrayList<>();
        for (Friend friend : all) {
            if (friend.label().toLowerCase().contains(needle) || friend.profile.id.contains(needle)) {
                out.add(friend);
            }
        }
        return out;
    }

    private Component status(String friendId, Presence presence) {
        if (!presence.online()) {
            return Component.translatable("netplayer.status.offline");
        }
        if (presence.hosting()) {
            Component base = Component.translatable(presence.openTo(client.me().id)
                    ? "netplayer.status.hosting_open" : "netplayer.status.hosting", presence.world);
            return presence.premium ? base.copy().append(" ")
                    .append(Component.translatable("netplayer.tag.premium")) : base;
        }
        return switch (presence.state) {
            case "playing", "hosting" -> Component.translatable("netplayer.status.playing", presence.detail);
            case "server" -> Component.translatable("netplayer.status.server", presence.detail);
            default -> Component.translatable("netplayer.status.online");
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("netplayer.friends.hint"), width / 2, 21, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("netplayer.friends.count", client.friends().size()),
                width / 2 - 155, height - 40, 0x808080, false);
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
