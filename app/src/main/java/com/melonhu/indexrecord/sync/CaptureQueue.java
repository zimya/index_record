package com.melonhu.indexrecord.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * File-backed capture queue; a published .meta is the commit marker.
 * Can also be used with a separate diagnostic directory.
 */
public final class CaptureQueue {

    interface Preparation {
        void run() throws Exception;
    }

    interface PayloadFilter {
        boolean keep(File payload) throws Exception;
    }

    interface Sender {
        boolean send(Entry entry) throws Exception;
    }

    static final class Entry {

        final String token;
        final File meta;
        final File payload;
        final String url;
        final String hostApp;
        final String contentType;
        final long timestamp;

        Entry(
            String token,
            File meta,
            File payload,
            String url,
            String hostApp,
            String contentType,
            long timestamp
        ) {
            this.token = token;
            this.meta = meta;
            this.payload = payload;
            this.url = url;
            this.hostApp = hostApp;
            this.contentType = contentType;
            this.timestamp = timestamp;
        }
    }

    private final File directory;
    private final BiConsumer<String, Exception> logger;
    private final AtomicBoolean syncing = new AtomicBoolean();

    public CaptureQueue(File directory, BiConsumer<String, Exception> logger) {
        this.directory = directory;
        this.logger = logger;
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create queue: " + directory);
        }
    }

    public void enqueue(
        String url,
        byte[] bytes,
        String contentType,
        String hostApp,
        long timestamp
    ) throws IOException {
        ensureDirectory();
        String token = timestamp + "_" + UUID.randomUUID();
        File payload = new File(directory, token + ".bin");
        File pending = new File(directory, token + ".meta.tmp");
        // Never publish metadata until the complete payload has been closed.
        try (FileOutputStream out = new FileOutputStream(payload)) {
            out.write(bytes);
            out.getFD().sync();
        }
        String meta =
            safeLine(url) +
            "\n" +
            safeLine(hostApp) +
            "\n" +
            safeLine(contentType) +
            "\n" +
            timestamp +
            "\n";
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(meta.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!pending.renameTo(new File(directory, token + ".meta"))) {
            throw new IOException("Cannot publish capture: " + token);
        }
    }

    private static String safeLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    static Entry readMeta(File meta) throws IOException {
        List<String> lines = Files.readAllLines(
            meta.toPath(),
            StandardCharsets.UTF_8
        );
        if (lines.size() < 2) throw new IOException(
            "Invalid capture metadata: " + meta
        );
        String token = meta.getName().substring(0, meta.getName().length() - 5);
        long timestamp =
            lines.size() >= 4 ? positiveLong(lines.get(3).trim()) : 0;
        if (timestamp == 0) {
            int separator = token.indexOf('_');
            timestamp = positiveLong(
                separator < 0 ? token : token.substring(0, separator)
            );
        }
        if (timestamp == 0) timestamp = System.currentTimeMillis();
        return new Entry(
            token,
            meta,
            new File(meta.getParentFile(), token + ".bin"),
            lines.get(0),
            lines.get(1),
            lines.size() >= 3 ? lines.get(2) : "",
            timestamp
        );
    }

    private static long positiveLong(String value) {
        try {
            long result = Long.parseLong(value);
            return result > 0 ? result : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    void sync(Preparation preparation, PayloadFilter filter, Sender sender) {
        if (!syncing.compareAndSet(false, true)) return;
        try {
            ensureDirectory();
            // Keep this lock file: unlinking it can let two processes lock different inodes.
            try (
                RandomAccessFile file = new RandomAccessFile(
                    new File(directory, ".sync.lock"),
                    "rw"
                )
            ) {
                FileLock lock;
                try {
                    lock = file.getChannel().tryLock();
                } catch (OverlappingFileLockException busy) {
                    return;
                }
                if (lock == null) return;
                try (FileLock held = lock) {
                    if (preparation != null) preparation.run();
                    File[] metas = directory.listFiles((dir, name) ->
                        name.endsWith(".meta")
                    );
                    if (metas == null) throw new IOException(
                        "Cannot list queue: " + directory
                    );
                    Arrays.sort(metas, Comparator.comparing(File::getName));
                    for (File meta : metas) {
                        String token = meta
                            .getName()
                            .substring(0, meta.getName().length() - 5);
                        if (
                            !new File(directory, token + ".bin").isFile()
                        ) continue;
                        Entry entry;
                        try {
                            entry = readMeta(meta);
                        } catch (IOException error) {
                            logger.accept(
                                "Cannot read capture metadata; retained " +
                                    meta,
                                error
                            );
                            continue;
                        }
                        if (filter != null && !filter.keep(entry.payload)) {
                            deletePair(entry);
                            continue;
                        }
                        if (!sender.send(entry)) {
                            logger.accept(
                                "Capture transfer failed; retained " + token,
                                null
                            );
                            return;
                        }
                        deletePair(entry);
                    }
                }
            }
        } catch (Exception error) {
            logger.accept(
                "Queue sync stopped; pending files retained in " + directory,
                error
            );
        } finally {
            syncing.set(false);
        }
    }

    private static void deletePair(Entry entry) throws IOException {
        // Remove the commit marker first so a failed payload deletion cannot cause re-delivery.
        Files.delete(entry.meta.toPath());
        Files.delete(entry.payload.toPath());
    }
}
