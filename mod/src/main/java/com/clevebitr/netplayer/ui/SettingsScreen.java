package com.clevebitr.netplayer.ui;

import com.clevebitr.netplayer.NetPlayerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Signaling server / STUN / proxy configuration, every field labelled. */
public final class SettingsScreen extends Screen {
    private static final int LABEL = 0xFFFFFF55;
    private static final int HINT = 0xFF909090;

    private final Screen parent;
    private final NetPlayerClient client = NetPlayerClient.get();
    private EditBox addressBox;
    private EditBox stunBox;
    private EditBox proxyBox;
    private EditBox proxyPortBox;
    private Component status = Component.empty();
    private int statusColor = 0xFF55FF55;

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

        stunBox = new EditBox(font, left, 96, 310, 18, Component.literal("stun.miwifi.com:3478"));
        stunBox.setMaxLength(512);
        stunBox.setValue(client.stunServersText());
        addRenderableWidget(stunBox);

        proxyBox = new EditBox(font, left, 146, 220, 18, Component.translatable("netplayer.settings.proxy_hint"));
        proxyBox.setMaxLength(128);
        proxyBox.setValue(client.proxyHost());
        addRenderableWidget(proxyBox);

        proxyPortBox = new EditBox(font, left + 226, 146, 84, 18, Component.literal("7890"));
        proxyPortBox.setMaxLength(5);
        proxyPortBox.setValue(Integer.toString(client.proxyPort()));
        addRenderableWidget(proxyPortBox);

        addRenderableWidget(Button.builder(Component.translatable("netplayer.settings.save"), b -> save())
                .bounds(left, 176, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("netplayer.settings.test"), b -> {
            client.probeSignaling();
            setStatus("netplayer.settings.probing", 0xFFFFAA00);
        }).bounds(left + 160, 176, 150, 20)
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
            setStatus("netplayer.settings.bad_host", 0xFFFF5555);
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
                setStatus("netplayer.settings.bad_port", 0xFFFF5555);
                return;
            }
            if (host.isEmpty()) {
                setStatus("netplayer.settings.bad_host", 0xFFFF5555);
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
        setStatus("netplayer.settings.saved", 0xFF55FF55);
    }

    private void setStatus(String key, int color) {
        status = Component.translatable(key);
        statusColor = color;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        boolean up = client.signalReachable();
        graphics.drawString(font, Component.translatable(up
                        ? "netplayer.status.signal_connected" : "netplayer.status.signal_offline"),
                left, 26, up ? 0xFF55FF55 : 0xFFFFAA00, false);
        graphics.drawString(font, Component.literal(client.signalingAddress()), left + 96, 26, HINT, false);

        graphics.drawString(font, Component.translatable("netplayer.settings.address"), left, 38, LABEL, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.stun_label"), left, 86, LABEL, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.proxy_label"), left, 136, LABEL, false);
        graphics.drawString(font, Component.translatable("netplayer.settings.proxy_port"), left + 226, 136, LABEL, false);

        if (!status.getString().isEmpty()) {
            graphics.drawString(font, status, left, 202, statusColor, false);
        }
        String probe = client.probeResult();
        if (!probe.isEmpty()) {
            Component text = probe.startsWith("netplayer.") ? Component.translatable(probe) : Component.literal(probe);
            graphics.drawString(font, text, left, 216, probe.equals("netplayer.settings.probe_ok") ? 0xFF55FF55
                    : probe.equals("netplayer.settings.probing") ? 0xFFFFAA00 : 0xFFFF5555, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
