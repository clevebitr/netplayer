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
    private final List<String> rowFriendIds = new ArrayList<>();
    private final List<Integer> rowTops = new ArrayList<>();
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

        search = new EditBox(font, left, 32, 150, 18, Component.translatable("netplayer.friends.search"));
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
                .bounds(left + 154, 32, 76, 18)
                .tooltip(Tooltip.create(Component.translatable("netplayer.friends.add_tip")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.settings"),
                        b -> minecraft.setScreen(new SettingsScreen(this)))
                .bounds(left + 234, 32, 76, 18)
                .tooltip(Tooltip.create(Component.translatable("netplayer.settings.tooltip")))
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
        rowFriendIds.clear();
        rowTops.clear();

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
            Component label = Component.literal(friend.label()).append("   ").append(status(friend.profile.id, presence));
            rows.add(addRow(Button.builder(label,
                            b -> minecraft.setScreen(new FriendDetailScreen(this, friend.profile.id)))
                    .bounds(left + 22, y, 288, 20)
                    .tooltip(Tooltip.create(Component.translatable("netplayer.friends.detail_tip")))
                    .build()));
            rowFriendIds.add(friend.profile.id);
            rowTops.add(y);
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
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("netplayer.friends.hint"), width / 2, 21, 0xFFA0A0A0);

        for (int i = 0; i < rowFriendIds.size(); i++) {
            Friend friend = client.friendMap().get(rowFriendIds.get(i));
            if (friend != null) {
                Heads.draw(graphics, friend.mcName, friend.mcUuid, left + 3, rowTops.get(i) + 2, 16);
            }
        }

        boolean up = client.connected();
        graphics.drawString(font, Component.translatable(up
                        ? "netplayer.status.signal_connected" : "netplayer.status.signal_offline"),
                left, height - 42, up ? 0xFF55FF55 : 0xFFFFAA00, false);
        graphics.drawString(font, Component.translatable("netplayer.friends.count", client.friends().size()),
                left, height - 52, 0xFF808080, false);
        if (!up) {
            graphics.drawString(font, Component.translatable("netplayer.status.direct_mode"),
                    left, height - 62, 0xFFFFCC00, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
