package marcy.voxysetup.engine;

import marcy.voxysetup.MarcysVoxySetup;
import marcy.voxysetup.config.SetupConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class VersionChecker {

    public enum State {
        UNCHECKED,
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        CHECK_FAILED
    }

    private static State state = State.UNCHECKED;
    private static String cachedRemoteVersion = null;
    private static final AtomicBoolean checkInProgress = new AtomicBoolean(false);

    public static boolean isVoxyInstalled() {
        return ModList.get().isLoaded("voxy");
    }

    public static Optional<String> getInstalledVersion() {
        return ModList.get().getModContainerById("voxy")
                .map(container -> container.getModInfo().getVersion().toString());
    }

    public static Optional<String> getRemoteVersion() {
        return Optional.ofNullable(cachedRemoteVersion);
    }

    public static State getState() {
        return state;
    }

    public static boolean hasUpdate() {
        return state == State.UPDATE_AVAILABLE;
    }

    public static void checkAsync(Runnable onResult) {
        if (!SetupConfig.get().checkForUpdates) {
            state = State.UP_TO_DATE;
            return;
        }

        if (state != State.UNCHECKED && state != State.CHECK_FAILED) {
            if (onResult != null) {
                Minecraft.getInstance().execute(onResult);
            }
            return;
        }

        if (!checkInProgress.compareAndSet(false, true)) {
            return;
        }

        state = State.CHECKING;
        Thread checkThread = new Thread(() -> {
            try {
                String remoteVer = fetchRemoteVersion();
                cachedRemoteVersion = remoteVer;

                Optional<String> installedVerOpt = getInstalledVersion();
                if (installedVerOpt.isPresent()) {
                    String installedVer = installedVerOpt.get();
                    int cmp = compareVersions(remoteVer, installedVer);
                    MarcysVoxySetup.LOGGER.info("Version check: Installed={}, Remote={}, cmp={}", installedVer, remoteVer, cmp);
                    if (cmp > 0) {
                        state = State.UPDATE_AVAILABLE;
                    } else {
                        state = State.UP_TO_DATE;
                    }
                } else {
                    // Not installed
                    state = State.UPDATE_AVAILABLE;
                }
            } catch (Exception e) {
                MarcysVoxySetup.LOGGER.warn("Failed to check remote NeoVoxy version: {}", e.getMessage());
                state = State.CHECK_FAILED;
            } finally {
                checkInProgress.set(false);
                if (onResult != null) {
                    Minecraft.getInstance().execute(onResult);
                }
            }
        }, "NeoVoxy-VersionCheck-Thread");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private static String fetchRemoteVersion() throws Exception {
        String checkUrl = SetupConfig.get().versionCheckUrl;
        URL url = URI.create(checkUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setInstanceFollowRedirects(true);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        Properties props = new Properties();
        props.load(new StringReader(sb.toString()));
        String modVersion = props.getProperty("mod_version");
        if (modVersion == null || modVersion.isBlank()) {
            throw new IllegalStateException("mod_version property not found in remote gradle.properties");
        }
        return modVersion.trim();
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null) return v2 == null ? 0 : -1;
        if (v2 == null) return 1;

        String clean1 = cleanVersion(v1);
        String clean2 = cleanVersion(v2);

        String[] parts1 = clean1.split("\\.");
        String[] parts2 = clean2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length ? parsePart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parsePart(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    private static String cleanVersion(String v) {
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int dashIdx = v.indexOf('-');
        if (dashIdx != -1) {
            v = v.substring(0, dashIdx);
        }
        int plusIdx = v.indexOf('+');
        if (plusIdx != -1) {
            v = v.substring(0, plusIdx);
        }
        return v.trim();
    }

    private static int parsePart(String s) {
        try {
            String digits = s.replaceAll("\\D", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }
}
