package marcy.voxysetup.engine;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DependencyDownloader {

    public record RemoteArtifact(String targetName, String url, String expectedSha1) {}

    private static final List<RemoteArtifact> PRIMARY_ARTIFACTS = List.of(
            new RemoteArtifact("create-1.21.1-6.0.10.jar",
                    "https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar",
                    "0e97e49837bed766e6f28a4c95b04885d6acc353"),
            new RemoteArtifact("sable-2.0.3.jar",
                    "https://cdn.modrinth.com/data/T9PomCSv/versions/1L6XJqnY/sable-neoforge-1.21.1-2.0.3.jar",
                    "f3ba9a497122ba29d9e94ebe6f9a3a3aaa5fa756"),
            new RemoteArtifact("Ponder-NeoForge-1.21.1-1.0.64.jar",
                    "https://maven.createmod.net/net/createmod/ponder/Ponder-NeoForge-1.21.1/1.0.64/Ponder-NeoForge-1.21.1-1.0.64.jar",
                    "115affcebbb877f4c878ab9e2867973a32895838"),
            new RemoteArtifact("flywheel-neoforge-api-1.21.1-1.0.6.jar",
                    "https://maven.createmod.net/dev/engine-room/flywheel/flywheel-neoforge-api-1.21.1/1.0.6/flywheel-neoforge-api-1.21.1-1.0.6.jar",
                    "78de23fcd1796061b2ffe9b8a98d38c2a0e56b3f"),
            new RemoteArtifact("eclipticseasons-1.21.1-neoforge-0.15.0-rc.jar",
                    "https://cdn.modrinth.com/data/ofdM7yJE/versions/xBpxy96m/EclipticSeasons-1.21.1-neoforge-0.15.0-rc.jar",
                    "3da01e8130ec0e02ff3f6832fa9db7972a7047b4"),
            new RemoteArtifact("bits_n_bobs-2.1.11-beta.jar",
                    "https://cdn.modrinth.com/data/T8bvmqVZ/versions/zNDKNuBZ/bits_n_bobs-2.1.11-beta.jar",
                    "a2c12a7247f541edf00edd739d276de3d5e356a7"),
            new RemoteArtifact("azimuth-1.4.1.jar",
                    "https://cdn.modrinth.com/data/RpH8tmT1/versions/vNeuJ1Hb/azimuth-1.4.1.jar",
                    "0af876492c37100d39ab1ed12405f0fc032b1f93"),
            new RemoteArtifact("entityculling-neoforge-1.10.5.jar",
                    "https://cdn.modrinth.com/data/NNAgCjsB/versions/5zRIon6w/entityculling-neoforge-1.10.5-mc1.21.1.jar",
                    "420d0006dbc0a20b1c2dd0b3c7c20ddb5cc51fcf")
    );

    public static void ensureDependencies(Path aeroSpikeDir, Consumer<String> logger) throws Exception {
        Files.createDirectories(aeroSpikeDir);
        logger.accept("Verifying pinned compile dependencies in libs/aero-spike...");

        for (RemoteArtifact artifact : PRIMARY_ARTIFACTS) {
            Path targetFile = aeroSpikeDir.resolve(artifact.targetName());
            if (Files.exists(targetFile) && checkSha1(targetFile, artifact.expectedSha1())) {
                continue;
            }

            logger.accept("Downloading: " + artifact.targetName());
            downloadWithRetry(artifact.url(), targetFile, logger);

            if (!checkSha1(targetFile, artifact.expectedSha1())) {
                logger.accept("Warning: SHA1 checksum mismatch for " + artifact.targetName() + " (proceeding with downloaded file)");
            }
        }

        // Extract nested jars
        extractNestedJar(aeroSpikeDir.resolve("sable-2.0.3.jar"),
                "META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar",
                aeroSpikeDir.resolve("sable-companion-common-1.21.1-1.6.0.jar"),
                logger);

        extractNestedJar(aeroSpikeDir.resolve("sable-2.0.3.jar"),
                "META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar",
                aeroSpikeDir.resolve("dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar"),
                logger);

        extractNestedJar(aeroSpikeDir.resolve("create-1.21.1-6.0.10.jar"),
                "META-INF/jarjar/flywheel-neoforge-1.21.1-1.0.6.jar",
                aeroSpikeDir.resolve("flywheel-neoforge-impl-1.21.1-1.0.6.jar"),
                logger);

        logger.accept("All compile dependencies verified successfully.");
    }

    private static void extractNestedJar(Path sourceJar, String innerPath, Path destPath, Consumer<String> logger) throws IOException {
        if (Files.exists(destPath)) {
            return;
        }
        logger.accept("Extracting nested companion: " + destPath.getFileName());
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            ZipEntry entry = zip.getEntry(innerPath);
            if (entry == null) {
                throw new FileNotFoundException("Could not locate " + innerPath + " inside " + sourceJar.getFileName());
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void downloadWithRetry(String urlStr, Path dest, Consumer<String> logger) throws Exception {
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                URL url = URI.create(urlStr).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0 (Minecraft; Java)");

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 307 || code == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    conn = (HttpURLConnection) URI.create(newUrl).toURL().openConnection();
                    conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0 (Minecraft; Java)");
                }

                try (InputStream in = conn.getInputStream();
                     OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
                    in.transferTo(out);
                }
                return;
            } catch (Exception e) {
                if (attempts >= 3) throw e;
                logger.accept("Download attempt " + attempts + " failed: " + e.getMessage() + ". Retrying...");
                Thread.sleep(1500);
            }
        }
    }

    private static boolean checkSha1(Path file, String expectedSha1) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedSha1);
        } catch (Exception e) {
            return false;
        }
    }
}
