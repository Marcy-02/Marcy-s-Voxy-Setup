package marcy.voxysetup.client;

import marcy.voxysetup.MarcysVoxySetup;
import marcy.voxysetup.config.SetupConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = MarcysVoxySetup.MOD_ID, value = Dist.CLIENT)
public class TitleScreenEventHandler {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen titleScreen) {
            // Check if NeoVoxy is already loaded
            if (ModList.get().isLoaded("voxy")) {
                return;
            }

            if (!SetupConfig.get().showTitleScreenButton) {
                return;
            }

            // Button size & position
            int buttonWidth = 130;
            int buttonHeight = 20;

            // Place in top-right or top-left corner
            int x = 8;
            int y = 8;

            Button setupBtn = Button.builder(
                    Component.literal("⚡ Setup NeoVoxy"),
                    btn -> Minecraft.getInstance().setScreen(new VoxySetupScreen(titleScreen))
            )
            .bounds(x, y, buttonWidth, buttonHeight)
            .tooltip(Tooltip.create(Component.literal("Click to automatically compile and install NeoVoxy (Create shadows & Sable LOD)")))
            .build();

            event.addListener(setupBtn);
        }
    }
}
