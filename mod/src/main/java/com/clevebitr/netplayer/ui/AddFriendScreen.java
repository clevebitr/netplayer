package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Paste a friend key, or copy your own. */
public final class AddFriendScreen extends Screen {
    private static final int KEY_LENGTH = 4096;

    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox keyBox;
    private Component status = Component.empty();
    private int statusColor = 0xA0A0A0;

    public AddFriendScreen(Screen parent) {
        super(Component.translatable("netplayer.add.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        client.init();
        int left = width / 2 - 155;

        keyBox = new EditBox(font, left, 56, 310, 18, Component.translatable("netplayer.add.hint"));
        keyBox.setMaxLength(KEY_LENGTH);
        keyBox.setTooltip(Tooltip.create(Component.translatable("netplayer.add.hint")));
        addRenderableWidget(keyBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.add"), b -> submit())
                .bounds(left, 80, 150, 20).build());

        EditBox mine = new EditBox(font, left, 136, 310, 18, Component.empty());
        mine.setMaxLength(KEY_LENGTH);
        mine.setValue(client.myFriendKey());
        mine.setEditable(false);
        addRenderableWidget(mine);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.copy_mine"), b -> {
            minecraft.keyboardHandler.setClipboard(client.myFriendKey());
            setStatus("netplayer.add.copied", 0x55FF55);
        }).bounds(left, 160, 310, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left, height - 28, 310, 20).build());

        setStatus("netplayer.add.tip", 0xA0A0A0);
    }

    private void submit() {
        String error = client.addFriend(keyBox.getValue());
        if (error == null) {
            minecraft.setScreen(parent);
            return;
        }
        setStatus(error, 0xFF5555);
    }

    private void setStatus(String key, int color) {
        status = Component.translatable(key);
        statusColor = color;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("netplayer.add.step1"), width / 2 - 155, 44, 0xFFFF00, false);
        graphics.drawString(font, Component.translatable("netplayer.add.step2"), width / 2 - 155, 124, 0xFFFF00, false);
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, status, width / 2 - 155, 104, statusColor, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
