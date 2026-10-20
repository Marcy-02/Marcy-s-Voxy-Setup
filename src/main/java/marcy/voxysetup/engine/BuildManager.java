package marcy.voxysetup.engine;

import marcy.voxysetup.config.SetupConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

        try {
            progress.update(0.05f, "Preparing workspace...");
            logger.accept("Workspace initialized at: " + workspaceDir);

            // 1. Fetch Source
            progress.update(0.15f, "Fetching latest NeoVoxy source code...");
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

            // Clean older Voxy jars to prevent duplicates and mod conflicts
            cleanOldVoxyJars(modsDir, destModJar.getFileName().toString(), logger);

            Files.copy(slimJar, destModJar, StandardCopyOption.REPLACE_EXISTING);
            logger.accept("Successfully deployed: " + destModJar);

            // 7. Clean up workspace: delete all intermediate build files, downloaded source, and JDK
            progress.update(0.98f, "Cleaning up workspace...");
            logger.accept("Deleting build workspace to reclaim disk space...");
            deleteDirectoryRecursively(workspaceDir);
            logger.accept("Workspace cleaned up. Temporary files deleted.");

            progress.update(1.0f, "Installation complete!");
            logger.accept("==================================================");
            logger.accept("NeoVoxy has been successfully built and installed!");
            logger.accept("Please restart Minecraft to load the new mod.");
            logger.accept("==================================================");
        } catch (InterruptedException e) {
            logger.accept("Build was cancelled.");
            deleteDirectoryRecursively(workspaceDir.resolve("voxy-source"));
            purgeGradleScriptCaches(null);
            throw e;
        } catch (Exception e) {
            deleteDirectoryRecursively(workspaceDir.resolve("voxy-source"));
            throw e;
        }
    }

    private static Path fetchSource(Path workspaceDir, Consumer<String> logger) throws Exception {
        Path sourceDir = workspaceDir.resolve("voxy-source");

        // Always clean any existing voxy-source to ensure fresh, up-to-date source files
        if (Files.exists(sourceDir)) {
            logger.accept("Cleaning previous source directory...");
            deleteDirectoryRecursively(sourceDir);
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

    private static void cleanOldVoxyJars(Path modsDir, String newJarName, Consumer<String> logger) {
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return (name.startsWith("neo-voxy-") || name.startsWith("voxy-"))
                        && name.endsWith(".jar")
                        && !name.equals(newJarName)
                        && !name.equals("voxy-server-side-neoforge.jar");
            }).forEach(oldJar -> {
                try {
                    Files.deleteIfExists(oldJar);
                    logger.accept("Removed older version: " + oldJar.getFileName());
                } catch (Exception e) {
                    oldJar.toFile().deleteOnExit();
                }
            });
        } catch (Exception ignored) {
        }
    }

    public static void deleteDirectoryRecursively(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    safeDelete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    safeDelete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void safeDelete(Path path) {
        for (int i = 0; i < 5; i++) {
            try {
                if (!Files.isWritable(path)) {
                    path.toFile().setWritable(true);
                }
                Files.deleteIfExists(path);
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        path.toFile().deleteOnExit();
    }

    public static void purgeGradleScriptCaches(Consumer<String> logger) {
        try {
            Path userHome = Paths.get(System.getProperty("user.home"));
            Path cachesDir = userHome.resolve(".gradle").resolve("caches");
            if (Files.isDirectory(cachesDir)) {
                try (var stream = Files.list(cachesDir)) {
                    stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("jars-") || name.startsWith("scripts");
                    }).forEach(p -> {
                        if (logger != null) {
                            logger.accept("Purging stale Gradle script cache: " + p.getFileName());
                        }
                        deleteDirectoryRecursively(p);
                    });
                }
            }
        } catch (Exception ignored) {
        }
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
                    throw new IOException("Zip entry attempted directory traversal: " + entry.getName());
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
        runGradleWithRetry(sourceDir, javaHome, logger, false);
    }

    private static void runGradleWithRetry(Path sourceDir, Path javaHome, Consumer<String> logger, boolean isRetry) throws Exception {
        if (cancelled) throw new InterruptedException("Build cancelled by user.");

        boolean isWin = JdkManager.isWindows();
        Path gradlew = sourceDir.resolve(isWin ? "gradlew.bat" : "gradlew");

        if (!isWin) {
            gradlew.toFile().setExecutable(true, false);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(gradlew.toAbsolutePath().toString());
        cmd.add("build");
        cmd.add("--no-daemon");
        cmd.add("--no-build-cache");
        cmd.add("--no-watch-fs");
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

        StringBuilder outputLog = new StringBuilder();

        // Pipe output
        Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), line -> {
            logger.accept(line);
            synchronized (outputLog) {
                outputLog.append(line).append("\n");
            }
        }));
        Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), line -> {
            logger.accept(line);
            synchronized (outputLog) {
                outputLog.append(line).append("\n");
            }
        }));
        stdoutThread.start();
        stderrThread.start();

        int exitCode = process.waitFor();
        runningProcess = null;
        stdoutThread.join();
        stderrThread.join();

        if (cancelled) {
            throw new InterruptedException("Build cancelled by user.");
        }

        if (exitCode != 0) {
            String allOutput = outputLog.toString();
            boolean isCacheCorruption = allOutput.contains("ClassNotFoundException")
                    || allOutput.contains("NoClassDefFoundError")
                    || allOutput.contains("Could not generate a decorated class")
                    || allOutput.contains("Could not compile build file")
                    || allOutput.contains("Could not compile settings file")
                    || allOutput.contains("CorruptIndexException")
                    || allOutput.contains("CorruptedCacheException")
                    || allOutput.contains("Could not initialize class")
                    || allOutput.contains("$_localJars_closure");

            if (!isRetry && isCacheCorruption) {
                logger.accept("==================================================");
                logger.accept("⚠️ Corrupted Gradle script cache detected.");
                logger.accept("Purging corrupted Gradle script caches and retrying automatically...");
                logger.accept("==================================================");

                purgeGradleScriptCaches(logger);
                deleteDirectoryRecursively(sourceDir.resolve(".gradle"));

                // Retry once
                runGradleWithRetry(sourceDir, javaHome, logger, true);
                return;
            }

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
