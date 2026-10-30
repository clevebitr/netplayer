package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Signaling server / STUN configuration. Accepts {@code host:port} or a {@code ws(s)://} url. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox addressBox;
    private EditBox stunBox;
    private EditBox proxyBox;
    private EditBox proxyPortBox;
    private Component status = Component.empty();
    private int statusColor = 0x55FF55;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("netplayer.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        client.init();
        int left = width / 2 - 155;

        addressBox = new EditBox(font, left, 48, 310, 18, Component.translatable("netplayer.settings.address_hint"));
        addressBox.setMaxLength(256);
        addressBox.setValue(address());
        addRenderableWidget(addressBox);

        stunBox = new EditBox(font, left, 96, 310, 18, Component.literal("stun"));
        stunBox.setMaxLength(512);
        stunBox.setValue(client.stunServersText());
        addRenderableWidget(stunBox);

        proxyBox = new EditBox(font, left, 138, 220, 18, Component.translatable("netplayer.settings.proxy_hint"));
        proxyBox.setMaxLength(128);
        proxyBox.setValue(client.proxyHost());
        addRenderableWidget(proxyBox);

        proxyPortBox = new EditBox(font, left + 226, 138, 84, 18, Component.literal("port"));
        proxyPortBox.setMaxLength(5);
        proxyPortBox.setValue(Integer.toString(client.proxyPort()));
        addRenderableWidget(proxyPortBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.settings.save"), b -> save())
                .bounds(left, 162, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.settings.test"), b -> {
            client.probeSignaling();
            setStatus("netplayer.settings.probing", 0xFFAA00);
        }).bounds(left + 160, 162, 150, 20)
                .tooltip(Tooltip.create(Component.translatable("netplayer.settings.test_tip")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.button.done"),
                        b -> minecraft.setScreen(parent))
                .bounds(left, height - 28, 310, 20).build());
    }

    private String address() {
        return client.signalingAddress();
    }

    private void save() {
        String raw = addressBox.getValue().strip();
        if (raw.isEmpty()) {
            setStatus("netplayer.settings.bad_host", 0xFF5555);
            return;
        }
        String host = raw;
        int port = 25566;
        if (!raw.contains("://")) {
            int colon = raw.lastIndexOf(':');
            if (colon > 0) {
                host = raw.substring(0, colon);
                try {
                    port = Integer.parseInt(raw.substring(colon + 1));
                } catch (Exception e) {
                    port = 0;
                }
            }
            if (port <= 0 || port > 65535) {
                setStatus("netplayer.settings.bad_port", 0xFF5555);
                return;
            }
            if (host.isEmpty()) {
                setStatus("netplayer.settings.bad_host", 0xFF5555);
                return;
            }
        }
        int proxyPort;
        try {
            proxyPort = Integer.parseInt(proxyPortBox.getValue().strip());
        } catch (Exception e) {
            proxyPort = 0;
        }
        client.applySettings(host, port, stunBox.getValue(), proxyBox.getValue(), Math.max(0, proxyPort));
        setStatus("netplayer.settings.saved", 0x55FF55);
    }

    private void setStatus(String key, int color) {
        status = Component.translatable(key);
        statusColor = color;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("netplayer.settings.address"), left, 36, 0xFFFF00, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.address_note"), left, 70, 0x808080, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.stun"), left, 84, 0xFFFF00, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.proxy"), left, 126, 0xFFFF00, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.stun_hint"), left, 116, 0x808080, false);

        Component state = Component.translatable(client.signalReachable()
                ? "netplayer.settings.state_connected" : "netplayer.settings.state_offline");
        graphics.drawString(font, state, left, 190, client.signalReachable() ? 0x55FF55 : 0xFFAA00, false);
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, status, left, 204, statusColor, false);
        }
        String probe = client.probeResult();
        if (!probe.isEmpty()) {
            Component text = probe.startsWith("netplayer.") ? Component.translatable(probe) : Component.literal(probe);
            graphics.drawString(font, text, left, 218, probe.equals("netplayer.settings.probe_ok")
                    ? 0x55FF55 : probe.equals("netplayer.settings.probing") ? 0xFFAA00 : 0xFF5555, false);
        }
        graphics.drawString(font, Component.translatable("netplayer.settings.note"), left, height - 42, 0x808080, false);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
