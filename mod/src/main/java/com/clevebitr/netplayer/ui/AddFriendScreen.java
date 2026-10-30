package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AddFriendScreen extends Screen {
    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox keyBox;
    private Component message = Component.empty();

    public AddFriendScreen(Screen parent) {
        super(Component.translatable("netplayer.add.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        client.init();
        int left = width / 2 - 155;

        keyBox = new EditBox(font, left, 56, 310, 18, Component.translatable("netplayer.add.hint"));
        addRenderableWidget(keyBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.add"), b -> submit())
                .bounds(left, 82, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.copy_mine"), b -> {
            minecraft.keyboardHandler.setClipboard(client.myFriendKey());
            message = Component.translatable("netplayer.add.copied");
        }).bounds(left + 160, 82, 150, 20).build());

        EditBox mine = new EditBox(font, left, 118, 310, 18, Component.empty());
        mine.setValue(client.myFriendKey());
        mine.setEditable(false);
        addRenderableWidget(mine);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left, height - 28, 310, 20).build());
    }

    private void submit() {
        String error = client.addFriend(keyBox.getValue());
        if (error == null) {
            minecraft.setScreen(parent);
        } else {
            message = Component.translatable(error);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("netplayer.add.mine_hint"),
                width / 2, 106, 0xA0A0A0);
        if (!message.getString().isEmpty()) {
            graphics.drawCenteredString(font, message, width / 2, height - 44, 0xFFAA00);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
