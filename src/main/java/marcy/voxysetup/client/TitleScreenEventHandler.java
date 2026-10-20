package marcy.voxysetup.client;

import marcy.voxysetup.MarcysVoxySetup;
import marcy.voxysetup.config.SetupConfig;
import marcy.voxysetup.engine.VersionChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = MarcysVoxySetup.MOD_ID, value = Dist.CLIENT)
public class TitleScreenEventHandler {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        if (!SetupConfig.get().showTitleScreenButton) {
            return;
        }

        int buttonWidth = 130;
        int buttonHeight = 20;

        // Horizontally center below the Options & Quit Game buttons
        int x = (titleScreen.width - buttonWidth) / 2;

        // Find the bottom Y position of the Options and Quit Game buttons
        int optionsQuitBottomY = -1;
        for (var listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget) {
                Component msg = widget.getMessage();
                if (msg != null) {
                    if (msg.getContents() instanceof TranslatableContents tc) {
                        String key = tc.getKey();
                        if ("menu.options".equals(key) || "menu.quit".equals(key)) {
                            optionsQuitBottomY = Math.max(optionsQuitBottomY, widget.getY() + widget.getHeight());
                        }
                    }
                    String text = msg.getString().toLowerCase(java.util.Locale.ROOT);
                    if (text.contains("options") || text.contains("quit") || text.contains("opzioni") || text.contains("esci")) {
                        optionsQuitBottomY = Math.max(optionsQuitBottomY, widget.getY() + widget.getHeight());
                    }
                }
            }
        }

        // Place 4px below Options & Quit Game; fallback to standard title screen layout row
        int y = optionsQuitBottomY > 0 ? (optionsQuitBottomY + 4) : (titleScreen.height / 4 + 48 + 72 + 24);

        // 1. If Voxy is not installed at all, show the initial setup button
        if (!VersionChecker.isVoxyInstalled()) {
            Button setupBtn = Button.builder(
                    Component.literal("⚡ Setup NeoVoxy"),
                    btn -> Minecraft.getInstance().setScreen(new VoxySetupScreen(titleScreen, false))
            )
            .bounds(x, y, buttonWidth, buttonHeight)
            .tooltip(Tooltip.create(Component.literal("Click to automatically compile and install Create: Voxyfied (with Create shadows & Sable LOD)")))
            .build();

            event.addListener(setupBtn);
            return;
        }

        // 2. Voxy is installed: check for updates
        if (VersionChecker.hasUpdate()) {
            Button updateBtn = Button.builder(
                    Component.literal("⬆ Update Voxy"),
                    btn -> Minecraft.getInstance().setScreen(new VoxySetupScreen(titleScreen, true))
            )
            .bounds(x, y, buttonWidth, buttonHeight)
            .tooltip(Tooltip.create(Component.literal("A newer version of Create: Voxyfied is available. Click to compile and update!")))
            .build();

            event.addListener(updateBtn);
        } else {
            // Kick off background check if not done yet
            VersionChecker.checkAsync(() -> {
                if (VersionChecker.hasUpdate()) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof TitleScreen ts) {
                        ts.init(mc, ts.width, ts.height);
                    }
                }
            });
        }
    }
}
