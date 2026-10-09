package marcy.voxysetup.engine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

public class JarTrimmer {

    private static final String ROCKS = "META-INF/jarjar/rocksdbjni-10.2.1.jar";
    private static final Set<String> ROCKS_KEEP = Set.of("librocksdbjni-win64.dll", "librocksdbjni-linux64.so");

    private static final String SQLITE = "META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar";
    private static final String SQLITE_NATIVE_PREFIX = "org/sqlite/native/";
    private static final String[] SQLITE_KEEP_DIRS = {
            "org/sqlite/native/Windows/x86_64/",
            "org/sqlite/native/Linux/x86_64/"
    };

    private static final Set<String> DEMODULARIZE = Set.of(
            "META-INF/jarjar/lwjgl-zstd-3.3.3.jar",
            "META-INF/jarjar/lwjgl-lmdb-3.3.3.jar"
    );
    private static final String MODULE_INFO = "META-INF/versions/9/module-info.class";

    public static Path trimJar(Path srcJar, Path dstJar, Consumer<String> logger) throws IOException {
        logger.accept("Starting JAR optimization on: " + srcJar.getFileName());

        Map<String, byte[]> transforms = new HashMap<>();

        try (ZipFile zipIn = new ZipFile(srcJar.toFile())) {
            ZipEntry rocksEntry = zipIn.getEntry(ROCKS);
            if (rocksEntry != null) {
                try (InputStream is = zipIn.getInputStream(rocksEntry)) {
                    transforms.put(ROCKS, filterRocks(is.readAllBytes(), logger));
                }
            }

            ZipEntry sqliteEntry = zipIn.getEntry(SQLITE);
            if (sqliteEntry != null) {
                try (InputStream is = zipIn.getInputStream(sqliteEntry)) {
                    transforms.put(SQLITE, filterSqlite(is.readAllBytes(), logger));
                }
            }

            for (String demodPath : DEMODULARIZE) {
                ZipEntry entry = zipIn.getEntry(demodPath);
                if (entry != null) {
                    try (InputStream is = zipIn.getInputStream(entry)) {
                        transforms.put(demodPath, demodularize(is.readAllBytes(), demodPath, logger));
                    }
                }
            }

            if (Files.exists(dstJar)) {
                Files.delete(dstJar);
            }

            try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dstJar)))) {
                Enumeration<? extends ZipEntry> entries = zipIn.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (transforms.containsKey(name)) {
                        byte[] modifiedBytes = transforms.get(name);
                        ZipEntry storedEntry = new ZipEntry(name);
                        storedEntry.setTime(entry.getTime());
                        storedEntry.setMethod(ZipEntry.STORED);
                        storedEntry.setSize(modifiedBytes.length);
                        storedEntry.setCompressedSize(modifiedBytes.length);

                        CRC32 crc = new CRC32();
                        crc.update(modifiedBytes);
                        storedEntry.setCrc(crc.getValue());

                        zout.putNextEntry(storedEntry);
                        zout.write(modifiedBytes);
                        zout.closeEntry();
                    } else {
                        ZipEntry copyEntry = new ZipEntry(name);
                        copyEntry.setTime(entry.getTime());
                        copyEntry.setMethod(entry.getMethod());
                        if (entry.getMethod() == ZipEntry.STORED) {
                            copyEntry.setSize(entry.getSize());
                            copyEntry.setCompressedSize(entry.getCompressedSize());
                            copyEntry.setCrc(entry.getCrc());
                        }

                        zout.putNextEntry(copyEntry);
                        try (InputStream is = zipIn.getInputStream(entry)) {
                            is.transferTo(zout);
                        }
                        zout.closeEntry();
                    }
                }
            }
        }

        long origSize = Files.size(srcJar) / 1024 / 1024;
        long slimSize = Files.size(dstJar) / 1024 / 1024;
        logger.accept(String.format("Jar trimming finished: %d MB -> %d MB", origSize, slimSize));
        return dstJar;
    }

    private static boolean isRocksNative(String name) {
        String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        return base.startsWith("librocksdbjni-") && (base.endsWith(".so") || base.endsWith(".dll") || base.endsWith(".jnilib"));
    }

    private static byte[] filterRocks(byte[] nested, Consumer<String> logger) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        int dropped = 0, kept = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(nested));
             ZipOutputStream zos = new ZipOutputStream(outBuf)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String base = e.getName().contains("/") ? e.getName().substring(e.getName().lastIndexOf('/') + 1) : e.getName();
                if (isRocksNative(e.getName()) && !ROCKS_KEEP.contains(base)) {
                    dropped++;
                    continue;
                }
                if (isRocksNative(e.getName())) {
                    kept++;
                }

                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zos.putNextEntry(copy);
                zis.transferTo(zos);
                zos.closeEntry();
            }
        }
        logger.accept(String.format("  RocksDB: kept %d natives, dropped %d", kept, dropped));
        return outBuf.toByteArray();
    }

    private static byte[] filterSqlite(byte[] nested, Consumer<String> logger) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        int dropped = 0, kept = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(nested));
             ZipOutputStream zos = new ZipOutputStream(outBuf)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith(SQLITE_NATIVE_PREFIX) && !name.endsWith("/")) {
                    boolean keep = false;
                    for (String prefix : SQLITE_KEEP_DIRS) {
                        if (name.startsWith(prefix)) {
                            keep = true;
                            break;
                        }
                    }
                    if (!keep) {
                        dropped++;
                        continue;
                    }
                    kept++;
                }

                ZipEntry copy = new ZipEntry(name);
                copy.setTime(e.getTime());
                zos.putNextEntry(copy);
                zis.transferTo(zos);
                zos.closeEntry();
            }
        }
        logger.accept(String.format("  SQLite: kept %d natives, dropped %d", kept, dropped));
        return outBuf.toByteArray();
    }

    private static byte[] demodularize(byte[] nested, String path, Consumer<String> logger) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        int removed = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(nested));
             ZipOutputStream zos = new ZipOutputStream(outBuf)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(MODULE_INFO)) {
                    removed++;
                    continue;
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zos.putNextEntry(copy);
                zis.transferTo(zos);
                zos.closeEntry();
            }
        }
        logger.accept(String.format("  Demodularized %s: removed module-info x%d", path, removed));
        return outBuf.toByteArray();
    }
}
