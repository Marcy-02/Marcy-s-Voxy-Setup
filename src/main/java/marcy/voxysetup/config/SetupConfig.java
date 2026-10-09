package marcy.voxysetup.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SetupConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SetupConfig INSTANCE;

    public String repositoryZipUrl = "https://github.com/Marcy-02/voxy/archive/refs/heads/main.zip";
    public String expectedVoxyVersion = "1.0.0";
    public boolean showTitleScreenButton = true;

    public static SetupConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static SetupConfig load() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("marcy_voxy_setup.json");
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                SetupConfig cfg = GSON.fromJson(reader, SetupConfig.class);
                if (cfg != null) return cfg;
            } catch (Exception ignored) {
            }
        }
        SetupConfig def = new SetupConfig();
        save(def);
        return def;
    }

    public static void save(SetupConfig config) {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve("marcy_voxy_setup.json");
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception ignored) {
        }
    }
}
