package io.cleanyourtv.ui;

import android.graphics.Bitmap;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Production Images.fetch and Tmdb.readBytes; all HTTP and file IO are real. */
public final class ArtworkCacheTest {
    static final long DAY = 24 * 3600_000L;
    static final int OLD = 0xffcc2233, NEW = 0xff2244cc;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) image.setRGB(x, y, color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        check(ImageIO.write(image, "png", out), "PNG encoder unavailable");
        return out.toByteArray();
    }
    static void image(Bitmap bitmap, int color) {
        check(bitmap != null, "Expected usable image, got null");
        check(bitmap.getPixel(0, 0) == color, "Wrong returned image");
    }
    /** Raw HTTP closes the socket at the exact byte count (including premature EOF). */
    static final class HttpFixture implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker;
        volatile boolean peerClosed;
        HttpFixture(byte[] body, long length, boolean offline) throws IOException {
            this(body, "HTTP/1.1 " + (offline ? "503 Unavailable" : "200 OK")
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n", false);
        }
        HttpFixture(byte[] body, String headers, boolean awaitDisconnect) throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            worker = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    requests.incrementAndGet();
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        check(!line.toLowerCase(java.util.Locale.ROOT).startsWith("range:"),
                                "Images unexpectedly requested a range");
                    }
                    OutputStream output = socket.getOutputStream();
                    output.write(headers.getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.flush();
                    if (awaitDisconnect) {
                        check(input.read() == -1, "Expected client disconnect after rejected response");
                        peerClosed = true;
                    }
                } catch (Throwable e) { if (!listener.isClosed()) error.set(e); }
            }, "loopback-artwork-fixture");
            worker.setDaemon(true);
            worker.start();
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/image.png"; }
        @Override public void close() throws Exception {
            listener.close();
            worker.join(3000);
            check(!worker.isAlive(), "HTTP fixture did not stop");
            check(error.get() == null, "HTTP fixture failed: " + error.get());
        }
    }
    /** JDK 17-21 permission hook injects IO denial, not a replacement filesystem. */
    @SuppressWarnings("removal")
    static final class DiskFault extends SecurityManager {
        final String mode, target;
        int denied;
        Thread pruner;
        final AtomicReference<Throwable> pruneError = new AtomicReference<>();
        DiskFault(String mode, Path target) { this.mode = mode; this.target = target.toString(); }
        @Override public void checkPermission(java.security.Permission permission) {}
        @Override public void checkDelete(String file) {
            if (mode.equals("cleanup_failure") && file.endsWith(".tmp")) {
                denied++;
                throw new SecurityException("Injected cleanup denial");
            }
        }
        @Override public void checkWrite(String file) {
            if (file.endsWith(".tmp") && new File(file).getParent().equals(new File(target).getParent())) {
                boolean exists = new File(file).exists();
                if ((mode.equals("write_failure") && exists) || (mode.equals("create_failure") && !exists)) {
                    denied++;
                    throw new SecurityException("Injected temporary-file write failure");
                }
            }
            if ((mode.equals("promotion_failure") || mode.equals("cleanup_failure") || mode.equals("prune_refresh")) && file.equals(target)) {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (frame.getClassName().equals("java.nio.file.Files") && frame.getMethodName().equals("move")) {
                        if (mode.equals("promotion_failure") || mode.equals("cleanup_failure")) {
                            denied++;
                            throw new SecurityException("Injected atomic promotion failure");
                        }
                        pruner = new Thread(() -> {
                            try {
                                java.lang.reflect.Method prune = Images.class.getDeclaredMethod("prune");
                                prune.setAccessible(true);
                                prune.invoke(null);
                            } catch (Throwable e) { pruneError.set(e); }
                        }, "concurrent-cache-prune");
                        pruner.setDaemon(true);
                        pruner.start();
                        long deadline = System.nanoTime() + 2_000_000_000L;
                        while (pruner.isAlive() && pruner.getState() != Thread.State.BLOCKED
                                && System.nanoTime() < deadline) Thread.yield();
                        check(pruner.getState() == Thread.State.BLOCKED,
                                "Prune ran through an in-flight cache promotion");
                    }
                }
            }
        }
    }
    /** Pause actual disk IO, not an artificial acquisition of Images' monitor. */
    static void pauseDisk(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { check(release.await(5, TimeUnit.SECONDS), "Disk hook release timed out"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("removal")
    static void warmInit(String mode, Path dir, Field field) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        byte[] fresh = png(NEW);
        try (HttpFixture server = new HttpFixture(fresh, fresh.length, false)) {
            if (mode.equals("init_prune")) {
                field.set(null, new File(dir.toString()) {
                    @Override public File[] listFiles() {
                        pauseDisk(entered, release);
                        return super.listFiles();
                    }
                });
            }
            SecurityManager hook = new SecurityManager() {
                boolean seen;
                @Override public void checkPermission(java.security.Permission permission) {}
                @Override public void checkWrite(String file) {
                    if (!seen && dir.toFile().equals(new File(file).getParentFile())) {
                        seen = true;
                        pauseDisk(entered, release);
                    }
                }
            };
            Thread worker = new Thread(() -> {
                try {
                    if (mode.equals("init_store")) image(Images.fetch(server.url()), NEW);
                    else {
                        java.lang.reflect.Method prune = Images.class.getDeclaredMethod("prune");
                        prune.setAccessible(true);
                        prune.invoke(null);
                    }
                } catch (Throwable t) { error.set(t); }
            }, "paused-cache-io");
            Thread foreground = new Thread(() -> {
                try { Images.init(null); completed.countDown(); }
                catch (Throwable t) { error.set(t); }
            }, "warm-foreground-init");
            boolean responsive;
            try {
                if (mode.equals("init_store")) System.setSecurityManager(hook);
                worker.start();
                check(entered.await(3, TimeUnit.SECONDS), "Actual disk IO hook not reached");
                foreground.start();
                responsive = completed.await(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                worker.join(3000);
                foreground.join(3000);
                if (mode.equals("init_store")) System.setSecurityManager(null);
            }
            check(!worker.isAlive() && !foreground.isAlive(), "Warm-init threads did not finish");
            check(error.get() == null, "Warm-init worker failed: " + error.get());
            check(responsive, "Warm init blocked behind paused " + mode);
        }
    }
    static void transportResponse(String mode, Path dir) throws Exception {
        byte[] old = png(OLD), fresh = png(NEW), wire = fresh;
        boolean chunked = mode.startsWith("transport_chunked"), truncated = mode.endsWith("truncated");
        String headers = "HTTP/1.1 200 OK\r\nConnection: close\r\n";
        if (chunked) {
            headers += "Transfer-Encoding: chunked\r\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write((Integer.toHexString(fresh.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(fresh);
            out.write((truncated ? "\r\n" : "\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            wire = out.toByteArray();
        }
        try (HttpFixture server = new HttpFixture(wire, headers + "\r\n", false)) {
            String url = server.url();
            Path target = dir.resolve(Integer.toHexString(url.hashCode()) + "-" + url.length());
            Files.write(target, old);
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));
            long stamp = Files.getLastModifiedTime(target).toMillis();
            image(Images.fetch(url), truncated ? OLD : NEW);
            check(Arrays.equals(truncated ? old : fresh, Files.readAllBytes(target)), "Wrong transport cache bytes");
            long after = Files.getLastModifiedTime(target).toMillis();
            check(truncated ? after == stamp : after > stamp, "Wrong transport TTL timestamp");
            check(server.requests.get() == 1, "Wrong transport request count");
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                check(files.count() == 1, "Transport left a staging file");
            }
        }
    }
    static void partialResponse(String mode, Path dir) throws Exception {
        byte[] old = png(OLD), fresh = png(NEW);
        byte[] body = Arrays.copyOf(fresh, fresh.length - 12); // Missing IEND, but ImageIO accepts it.
        image(android.graphics.BitmapFactory.decodeByteArray(body, 0, body.length,
                new android.graphics.BitmapFactory.Options()), NEW);
        String headers = "HTTP/1.1 " + (mode.startsWith("partial206") ? "206 Partial Content" : "200 OK")
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n";
        if (!mode.equals("partial206_no_range"))
            headers += "Content-Range: bytes 0-" + (body.length - 1) + "/" + fresh.length + "\r\n";
        try (HttpFixture server = new HttpFixture(body, headers + "\r\n", true)) {
            String url = server.url();
            Path target = dir.resolve(Integer.toHexString(url.hashCode()) + "-" + url.length());
            Files.write(target, old);
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));
            long stamp = Files.getLastModifiedTime(target).toMillis();
            Bitmap result = Images.fetch(url);
            server.worker.join(3000);
            check(!server.worker.isAlive() && server.error.get() == null, "Partial HTTP fixture failed: " + server.error.get());
            check(server.peerClosed, "Rejected response leaked its connection");
            check(Arrays.equals(old, Files.readAllBytes(target)), "Explicit partial response replaced stale bytes");
            check(Files.getLastModifiedTime(target).toMillis() == stamp, "Partial response changed TTL timestamp");
            image(result, OLD);
            check(server.requests.get() == 1, "Wrong partial request count");
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                check(files.count() == 1, "Partial response left a staging file");
            }
        }
    }
    @SuppressWarnings("removal")
    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        Path dir = Files.createTempDirectory(Paths.get(args[1]), "cache-");
        Field field = Images.class.getDeclaredField("dir");
        field.setAccessible(true);
        String mode = args[0];
        boolean missing = mode.startsWith("missing") || mode.equals("no_disk");
        boolean corrupt = mode.startsWith("corrupt");
        boolean offline = mode.endsWith("offline");
        boolean refresh = mode.endsWith("refresh");
        boolean diskFault = mode.endsWith("failure");
        boolean observeDisk = diskFault || mode.equals("prune_refresh");
        boolean interrupted = mode.startsWith("interrupted");
        field.set(null, mode.equals("no_disk") ? null : dir.toFile());
        if (mode.startsWith("init_")) {
            warmInit(mode, dir, field);
            System.out.println("PASS " + mode);
            return;
        }
        if (mode.startsWith("transport_")) {
            transportResponse(mode, dir);
            System.out.println("PASS " + mode);
            return;
        }
        if (mode.startsWith("partial")) {
            partialResponse(mode, dir);
            System.out.println("PASS " + mode);
            return;
        }
        byte[] old = png(OLD), fresh = png(NEW), bad = "not an image".getBytes(StandardCharsets.UTF_8);
        byte[] seed = corrupt ? bad : old;
        byte[] body = mode.equals("malformed") ? bad : mode.equals("empty") || offline ? new byte[0] : fresh;
        if (mode.equals("interrupted_truncated")) body = Arrays.copyOf(fresh, fresh.length / 2);
        try (HttpFixture server = new HttpFixture(body, interrupted ? fresh.length + 64 : body.length, offline)) {
            String url = server.url();
            Path target = dir.resolve(Integer.toHexString(url.hashCode()) + "-" + url.length());
            if (!missing) {
                Files.write(target, seed);
                if (!mode.equals("fresh"))
                    Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));
            }
            long stamp = missing ? 0 : Files.getLastModifiedTime(target).toMillis();
            DiskFault fault = new DiskFault(mode, target);
            Bitmap result;
            try {
                if (observeDisk) System.setSecurityManager(fault);
                result = Images.fetch(url);
            } finally {
                if (observeDisk) System.setSecurityManager(null);
                if (fault.pruner != null) {
                    fault.pruner.join(3000);
                    check(!fault.pruner.isAlive(), "Prune did not complete after promotion");
                    check(fault.pruneError.get() == null, "Prune failed: " + fault.pruneError.get());
                }
            }
            if (mode.equals("prune_refresh")) check(fault.pruner != null, "Promotion observation did not run");
            if (refresh) {
                image(result, NEW);
                check(Arrays.equals(fresh, Files.readAllBytes(target)), "Valid refresh was not promoted");
                check(Files.getLastModifiedTime(target).toMillis() > stamp, "Valid refresh did not update timestamp");
            } else {
                if (missing) check(!Files.exists(target), "Unexpected cache entry");
                else {
                    check(Arrays.equals(seed, Files.readAllBytes(target)), mode + " destroyed known-good stale cache");
                    check(Files.getLastModifiedTime(target).toMillis() == stamp, "Failed refresh changed TTL timestamp");
                }
                if (diskFault || mode.equals("no_disk")) image(result, NEW);
                else if (missing || corrupt) check(result == null, "Expected safe null without usable cache");
                else image(result, OLD);
            }
            if (diskFault) check(fault.denied == (mode.equals("cleanup_failure") ? 2 : 1),
                    "Disk fault did not fire the expected number of times");
            if (mode.equals("cleanup_failure")) {
                // The filesystem refused cleanup; an orphan is unavoidable. Remove it after lifting the fault.
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    Path[] orphans = files.filter(p -> p.toString().endsWith(".tmp")).toArray(Path[]::new);
                    check(orphans.length == 1, "Expected one denied-cleanup orphan");
                    for (Path orphan : orphans) Files.delete(orphan);
                }
            }
            check(server.requests.get() == (mode.equals("fresh") ? 0 : 1), "Wrong HTTP request count");
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                check(files.count() == (missing && !refresh ? 0 : 1), "Temporary files leaked");
            }
            System.out.println("PASS " + args[0]);
        }
    }
}
