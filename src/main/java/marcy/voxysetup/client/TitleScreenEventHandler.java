package marcy.voxysetup.client;

import marcy.voxysetup.MarcysVoxySetup;
import marcy.voxysetup.config.SetupConfig;
import marcy.voxysetup.engine.VersionChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
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

        int x = 8;
        int y = 8;
        int buttonHeight = 20;

        // 1. If Voxy is not installed at all, show the initial setup button
        if (!VersionChecker.isVoxyInstalled()) {
            int buttonWidth = 130;
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
            String btnText = "⬆ Update Voxy";
            int buttonWidth = 115;

            Button updateBtn = Button.builder(
                    Component.literal(btnText),
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
