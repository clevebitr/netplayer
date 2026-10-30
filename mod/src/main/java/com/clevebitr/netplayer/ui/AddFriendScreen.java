package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Paste any NetPlayer code: friend key, room key, or a direct offer/answer. */
public final class AddFriendScreen extends Screen {
    private static final int KEY_LENGTH = 8192;

    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox keyBox;
    private EditBox replyBox;
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

        keyBox = new EditBox(font, left, 46, 240, 18, Component.translatable("netplayer.add.hint"));
        keyBox.setMaxLength(KEY_LENGTH);
        keyBox.setTooltip(Tooltip.create(Component.translatable("netplayer.add.hint")));
        addRenderableWidget(keyBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.add.submit"), b -> submit())
                .bounds(left + 244, 46, 66, 18).build());

        EditBox mine = new EditBox(font, left, 108, 310, 18, Component.empty());
        mine.setMaxLength(KEY_LENGTH);
        mine.setValue(client.myFriendKey());
        mine.setEditable(false);
        addRenderableWidget(mine);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.copy_mine"), b -> {
            minecraft.keyboardHandler.setClipboard(client.myFriendKey());
            setStatus("netplayer.add.copied", 0x55FF55);
        }).bounds(left, 130, 310, 20).build());

        replyBox = new EditBox(font, left, 178, 240, 18, Component.empty());
        replyBox.setMaxLength(KEY_LENGTH);
        replyBox.setValue(client.lastDirectAnswer());
        replyBox.setEditable(false);
        addRenderableWidget(replyBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.copy_reply"), b -> {
            String code = client.lastDirectAnswer();
            if (!code.isEmpty()) {
                minecraft.keyboardHandler.setClipboard(code);
                setStatus("netplayer.add.copied", 0x55FF55);
            }
        }).bounds(left + 244, 178, 66, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left, height - 28, 310, 20).build());

        setStatus("netplayer.add.tip", 0x808080);
    }

    private void submit() {
        String error = client.applyCode(keyBox.getValue());
        if (error == null) {
            String reply = client.lastDirectAnswer();
            if (!reply.isEmpty() && replyBox != null) {
                replyBox.setValue(reply);
            }
            if (minecraft.screen == this && client.lastDirectAnswer().isEmpty()) {
                minecraft.setScreen(parent);
                return;
            }
            setStatus("netplayer.add.reply_ready", 0x55FF55);
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
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int left = width / 2 - 155;
        graphics.drawString(font, Component.translatable("netplayer.add.step1"), left, 34, 0xFFFF00, false);
        graphics.drawString(font, status, left, 70, statusColor, false);
        graphics.drawString(font, Component.translatable("netplayer.add.step2"), left, 96, 0xFFFF00, false);
        graphics.drawString(font, Component.translatable("netplayer.add.step3"), left, 156, 0xFFFF00, false);
        if (client.lastDirectAnswer().isEmpty()) {
            graphics.drawString(font, Component.translatable("netplayer.add.reply_empty"), left, 200, 0x707070, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
