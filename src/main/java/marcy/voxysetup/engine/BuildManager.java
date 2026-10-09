package marcy.voxysetup.engine;

import marcy.voxysetup.config.SetupConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BuildManager {

    public interface ProgressCallback {
        void update(float progress, String stepName);
    }

    private static volatile Process runningProcess = null;
    private static volatile boolean cancelled = false;

    public static void cancel() {
        cancelled = true;
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroyForcibly();
        }
    }

    public static boolean isCancelled() {
        return cancelled;
    }

    public static void runBuild(ProgressCallback progress, Consumer<String> logger) throws Exception {
        cancelled = false;
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path modsDir = FMLPaths.MODSDIR.get();
        Path workspaceDir = gameDir.resolve("neovoxy-builder-workspace");
        Files.createDirectories(workspaceDir);

        progress.update(0.05f, "Preparing workspace...");
        logger.accept("Workspace initialized at: " + workspaceDir);

        // 1. Fetch Source
        progress.update(0.15f, "Fetching NeoVoxy source code...");
        Path sourceDir = fetchSource(workspaceDir, logger);

        if (cancelled) throw new InterruptedException("Build cancelled by user.");

        // 2. Fetch compile-time dependencies
        progress.update(0.35f, "Preparing compile dependencies (libs/aero-spike)...");
        Path aeroSpikeDir = sourceDir.resolve("libs").resolve("aero-spike");
        DependencyDownloader.ensureDependencies(aeroSpikeDir, logger);

        if (cancelled) throw new InterruptedException("Build cancelled by user.");

        // 3. Locate or download JDK 21
        progress.update(0.50f, "Checking JDK 21 environment...");
        Path javaHome = JdkManager.ensureJdk(workspaceDir, logger);

        if (cancelled) throw new InterruptedException("Build cancelled by user.");

        // 4. Run Gradle Build
        progress.update(0.65f, "Compiling NeoVoxy (gradlew build)...");
        runGradle(sourceDir, javaHome, logger);

        if (cancelled) throw new InterruptedException("Build cancelled by user.");

        // 5. Trim native libraries to produce slim jar
        progress.update(0.85f, "Optimizing JAR size (stripping unused natives)...");
        Path buildLibsDir = sourceDir.resolve("build").resolve("libs");
        Path fullJar = findBuiltJar(buildLibsDir);
        Path slimJar = buildLibsDir.resolve(fullJar.getFileName().toString().replace(".jar", "-slim.jar"));
        JarTrimmer.trimJar(fullJar, slimJar, logger);

        // 6. Deploy to active mods folder
        progress.update(0.95f, "Installing to mods folder...");
        Files.createDirectories(modsDir);
        Path destModJar = modsDir.resolve(slimJar.getFileName().toString());
        Files.copy(slimJar, destModJar, StandardCopyOption.REPLACE_EXISTING);
        logger.accept("Successfully deployed: " + destModJar);

        progress.update(1.0f, "Installation complete!");
        logger.accept("==================================================");
        logger.accept("NeoVoxy has been successfully built and installed!");
        logger.accept("Please restart Minecraft to load the new mod.");
        logger.accept("==================================================");
    }

    private static Path fetchSource(Path workspaceDir, Consumer<String> logger) throws Exception {
        Path sourceDir = workspaceDir.resolve("voxy-source");

        // If local voxy-source already has build.gradle, reuse it
        if (Files.isRegularFile(sourceDir.resolve("build.gradle"))) {
            logger.accept("Using existing NeoVoxy source directory: " + sourceDir);
            return sourceDir;
        }

        String repoZipUrl = SetupConfig.get().repositoryZipUrl;
        logger.accept("Downloading source from: " + repoZipUrl);
        Path tempZip = workspaceDir.resolve("source-archive.zip");

        URL url = URI.create(repoZipUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "NeoVoxy-Setup/1.0");
        conn.setInstanceFollowRedirects(true);

        try (InputStream in = conn.getInputStream();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempZip))) {
            in.transferTo(out);
        }

        logger.accept("Extracting source archive...");
        Files.createDirectories(sourceDir);
        unzipArchive(tempZip, sourceDir);
        Files.deleteIfExists(tempZip);

        // If repo unzipped into an inner root folder (e.g. NeoVoxy-main), find the directory containing build.gradle
        Path detectedRoot = findProjectRoot(sourceDir);
        logger.accept("Project root identified at: " + detectedRoot);
        return detectedRoot;
    }

    private static Path findProjectRoot(Path base) {
        if (Files.isRegularFile(base.resolve("build.gradle"))) return base;
        try (var s = Files.walk(base, 2)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("build.gradle")))
                    .findFirst()
                    .orElse(base);
        } catch (Exception e) {
            return base;
        }
    }

    private static void unzipArchive(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path dest = targetDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(targetDir)) {
                    throw new SecurityException("Zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void runGradle(Path sourceDir, Path javaHome, Consumer<String> logger) throws Exception {
        boolean isWin = JdkManager.isWindows();
        Path gradlew = sourceDir.resolve(isWin ? "gradlew.bat" : "gradlew");

        if (!isWin) {
            gradlew.toFile().setExecutable(true, false);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(gradlew.toAbsolutePath().toString());
        cmd.add("build");
        cmd.add("--no-daemon");
        cmd.add("-x");
        cmd.add("test");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(sourceDir.toFile());
        pb.environment().put("JAVA_HOME", javaHome.toAbsolutePath().toString());

        // Ensure PATH includes the JDK bin
        String pathEnv = pb.environment().getOrDefault("PATH", System.getenv("PATH"));
        String jdkBin = javaHome.resolve("bin").toAbsolutePath().toString();
        pb.environment().put("PATH", jdkBin + File.pathSeparator + pathEnv);

        logger.accept("Running: " + String.join(" ", cmd));
        logger.accept("Using JAVA_HOME: " + javaHome.toAbsolutePath());

        Process process = pb.start();
        runningProcess = process;

        // Pipe output
        Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), logger));
        Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), logger));
        stdoutThread.start();
        stderrThread.start();

        int exitCode = process.waitFor();
        runningProcess = null;
        stdoutThread.join();
        stderrThread.join();

        if (exitCode != 0) {
            throw new RuntimeException("Gradle build failed with exit code: " + exitCode);
        }
    }

    private static void readStream(InputStream is, Consumer<String> logger) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.accept(line);
            }
        } catch (Exception ignored) {
        }
    }

    private static Path findBuiltJar(Path buildLibsDir) throws IOException {
        if (!Files.isDirectory(buildLibsDir)) {
            throw new FileNotFoundException("build/libs directory does not exist: " + buildLibsDir);
        }
        try (var stream = Files.list(buildLibsDir)) {
            return stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("neo-voxy-") && name.endsWith(".jar")
                        && !name.endsWith("-slim.jar")
                        && !name.endsWith("-sources.jar")
                        && !name.endsWith("-javadoc.jar");
            }).findFirst().orElseThrow(() -> new FileNotFoundException("Could not find built neo-voxy jar in " + buildLibsDir));
        }
    }
}
