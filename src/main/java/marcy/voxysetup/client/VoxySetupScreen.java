package marcy.voxysetup.client;

import marcy.voxysetup.engine.BuildManager;
import marcy.voxysetup.engine.VersionChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VoxySetupScreen extends Screen {

    public enum State {
        IDLE,
        BUILDING,
        SUCCESS,
        FAILED
    }

    private final Screen parent;
    private final boolean isUpdate;
    private State state = State.IDLE;
    private float progress = 0.0f;
    private String stepText;
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());

    private Button startButton;
    private Button actionButton;
    private Thread workerThread;

    public VoxySetupScreen(Screen parent) {
        this(parent, false);
    }

    public VoxySetupScreen(Screen parent, boolean isUpdate) {
        super(Component.literal(isUpdate ? "NeoVoxy Automatic Update" : "NeoVoxy Automatic Setup"));
        this.parent = parent;
        this.isUpdate = isUpdate;
        this.stepText = isUpdate ? "Ready to update Create: Voxyfied." : "Ready to build and install NeoVoxy.";
    }

    @Override
    protected void init() {
        this.clearWidgets();

        int centerX = this.width / 2;
        int bottomY = this.height - 36;

        if (state == State.IDLE) {
            String startLabel = isUpdate ? "⚡ Start Update" : "⚡ Start Setup";
            startButton = Button.builder(Component.literal(startLabel), btn -> startBuild())
                    .bounds(centerX - 155, bottomY, 150, 20)
                    .build();
            this.addRenderableWidget(startButton);

            actionButton = Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                    .bounds(centerX + 5, bottomY, 150, 20)
                    .build();
            this.addRenderableWidget(actionButton);
        } else if (state == State.BUILDING) {
            actionButton = Button.builder(Component.literal("Cancel Build"), btn -> {
                BuildManager.cancel();
                this.stepText = "Cancelling build...";
                btn.active = false;
            }).bounds(centerX - 75, bottomY, 150, 20).build();
            this.addRenderableWidget(actionButton);
        } else if (state == State.SUCCESS) {
            actionButton = Button.builder(Component.literal("Close Game"), btn -> Minecraft.getInstance().stop())
                    .bounds(centerX - 75, bottomY, 150, 20)
                    .build();
            this.addRenderableWidget(actionButton);
        } else if (state == State.FAILED) {
            startButton = Button.builder(Component.literal("Retry"), btn -> startBuild())
                    .bounds(centerX - 155, bottomY, 150, 20)
                    .build();
            this.addRenderableWidget(startButton);

            actionButton = Button.builder(Component.literal("Close"), btn -> this.onClose())
                    .bounds(centerX + 5, bottomY, 150, 20)
                    .build();
            this.addRenderableWidget(actionButton);
        }
    }

    private void startBuild() {
        this.state = State.BUILDING;
        this.progress = 0.0f;
        this.logLines.clear();
        this.stepText = isUpdate ? "Starting update..." : "Initializing...";
        this.init();

        workerThread = new Thread(() -> {
            try {
                BuildManager.runBuild(
                        (pct, step) -> {
                            this.progress = pct;
                            this.stepText = step;
                        },
                        line -> {
                            logLines.add(line);
                            if (logLines.size() > 500) {
                                logLines.remove(0);
                            }
                        }
                );
                this.state = State.SUCCESS;
                this.stepText = isUpdate
                        ? "✓ NeoVoxy updated! Please close and restart the game."
                        : "✓ NeoVoxy installed! Please close and restart the game.";
            } catch (Exception e) {
                this.state = State.FAILED;
                this.stepText = "✗ Build failed: " + e.getMessage();
                logLines.add("ERROR: " + e.getMessage());
            } finally {
                Minecraft.getInstance().execute(this::init);
            }
        }, "NeoVoxy-Build-Thread");
        workerThread.start();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1. Render background first: vanilla blurs the panorama behind the screen
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // 2. Title and header - crisp, sharp, unblurred
        String title = isUpdate ? "⬆ Marcy's NeoVoxy Update" : "⚡ Marcy's NeoVoxy Setup";
        String subtitle = isUpdate
                ? "Updating Create: Voxyfied to the latest version"
                : "Automated in-game builder with Create distant shadows & Sable LOD";

        graphics.drawCenteredString(this.font, Component.literal(title), centerX, 16, 0xFFFFAA);
        graphics.drawCenteredString(this.font, Component.literal(subtitle), centerX, 30, 0xAAAAAA);

        // 3. Progress Card Container
        int cardLeft = 40;
        int cardRight = this.width - 40;
        int cardTop = 48;
        int cardBottom = this.height - 48;

        // Background box for console / card (solid dark color so background panorama cannot bleed through)
        graphics.fill(cardLeft, cardTop, cardRight, cardBottom, 0xF0101010);
        graphics.renderOutline(cardLeft, cardTop, cardRight - cardLeft, cardBottom - cardTop, 0xFF444444);

        // 4. Current Step Text
        int textColor = (state == State.SUCCESS) ? 0x55FF55 : (state == State.FAILED ? 0xFF5555 : 0xFFFFFF);
        graphics.drawString(this.font, Component.literal("Status: " + stepText), cardLeft + 10, cardTop + 10, textColor);

        // 5. Progress Bar
        int barX = cardLeft + 10;
        int barY = cardTop + 24;
        int barW = (cardRight - cardLeft) - 20;
        int barH = 10;

        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF222222);
        int filledW = (int) (barW * Math.min(1.0f, Math.max(0.0f, progress)));
        int barColor = (state == State.SUCCESS) ? 0xFF2ECC71 : (state == State.FAILED ? 0xFFE74C3C : 0xFF3498DB);
        graphics.fill(barX, barY, barX + filledW, barY + barH, barColor);
        graphics.renderOutline(barX, barY, barW, barH, 0xFF666666);

        // 6. Console Log Area
        int logBoxTop = barY + 16;
        int logBoxBottom = cardBottom - 10;
        graphics.fill(barX, logBoxTop, barX + barW, logBoxBottom, 0xFF050505);
        graphics.renderOutline(barX, logBoxTop, barW, logBoxBottom - logBoxTop, 0xFF2A2A2A);

        // 7. Draw last lines of the log
        int lineHeight = 10;
        int maxLines = (logBoxBottom - logBoxTop - 6) / lineHeight;

        synchronized (logLines) {
            int total = logLines.size();
            int startIdx = Math.max(0, total - maxLines);
            int currentY = logBoxTop + 4;

            for (int i = startIdx; i < total; i++) {
                String line = logLines.get(i);
                if (line.length() > 95) {
                    line = line.substring(0, 92) + "...";
                }
                graphics.drawString(this.font, Component.literal(line), barX + 6, currentY, 0xCCCCCC);
                currentY += lineHeight;
            }
        }

        // 8. Render buttons cleanly on top of everything
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void onClose() {
        if (state == State.BUILDING) {
            BuildManager.cancel();
        }
        Minecraft.getInstance().setScreen(this.parent);
    }
}
