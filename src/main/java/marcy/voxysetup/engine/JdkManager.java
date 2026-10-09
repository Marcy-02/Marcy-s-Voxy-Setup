package marcy.voxysetup.engine;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JdkManager {

    public static Path ensureJdk(Path workspaceDir, Consumer<String> logger) throws Exception {
        logger.accept("Locating Java Development Kit (JDK 21)...");

        // 1. Check current runtime java.home
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && isValidJdk(Paths.get(currentJavaHome))) {
            logger.accept("Found suitable JDK in current java.home: " + currentJavaHome);
            return Paths.get(currentJavaHome);
        }

        // 2. Check JAVA_HOME environment variable
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && isValidJdk(Paths.get(envJavaHome))) {
            logger.accept("Found suitable JDK in JAVA_HOME: " + envJavaHome);
            return Paths.get(envJavaHome);
        }

        // 3. Check workspace cached portable JDK
        Path portableJdkRoot = workspaceDir.resolve("portable-jdk21");
        Path existingPortable = findExtractedJdk(portableJdkRoot);
        if (existingPortable != null && isValidJdk(existingPortable)) {
            logger.accept("Using cached portable JDK: " + existingPortable);
            return existingPortable;
        }

        // 4. Download portable JDK from Adoptium
        logger.accept("No local JDK with javac found. Downloading portable OpenJDK 21 from Adoptium...");
        Files.createDirectories(portableJdkRoot);

        String downloadUrl = getAdoptiumUrl();
        Path archiveFile = portableJdkRoot.resolve("jdk21.zip");

        downloadFile(downloadUrl, archiveFile, logger);

        logger.accept("Extracting portable JDK 21...");
        unzip(archiveFile, portableJdkRoot);
        try {
            Files.deleteIfExists(archiveFile);
        } catch (Exception ignored) {
        }

        Path extractedJdk = findExtractedJdk(portableJdkRoot);
        if (extractedJdk == null || !isValidJdk(extractedJdk)) {
            throw new IllegalStateException("Downloaded JDK does not contain a valid javac compiler binary.");
        }

        logger.accept("Portable JDK 21 ready at: " + extractedJdk);
        return extractedJdk;
    }

    private static boolean isValidJdk(Path javaHome) {
        Path javac = isWindows() ? javaHome.resolve("bin").resolve("javac.exe") : javaHome.resolve("bin").resolve("javac");
        return Files.isRegularFile(javac);
    }

    private static Path findExtractedJdk(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.walk(dir, 2)) {
            return stream.filter(JdkManager::isValidJdk).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getAdoptiumUrl() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osName = "windows";
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac")) {
            osName = "mac";
        }

        String archName = "x64";
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archName = "aarch64";
        }

        return String.format("https://api.adoptium.net/v3/binary/latest/21/ga/%s/%s/jdk/hotspot/normal/eclipse", osName, archName);
    }

    private static void downloadFile(String urlStr, Path dest, Consumer<String> logger) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0");

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) URI.create(newUrl).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0");
        }

        long contentLength = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
            byte[] buf = new byte[65536];
            long totalRead = 0;
            int read;
            long lastReport = System.currentTimeMillis();

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                totalRead += read;
                if (System.currentTimeMillis() - lastReport > 2000) {
                    if (contentLength > 0) {
                        int pct = (int) ((totalRead * 100) / contentLength);
                        logger.accept(String.format("Downloading JDK: %d%% (%d MB / %d MB)", pct, totalRead / 1048576, contentLength / 1048576));
                    }
                    lastReport = System.currentTimeMillis();
                }
            }
        }
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new SecurityException("Zip slip detected in: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                    if (!isWindows() && resolved.toString().contains("/bin/")) {
                        try {
                            resolved.toFile().setExecutable(true, false);
                        } catch (Exception ignored) {
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
