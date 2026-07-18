package dev.ide.applog;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The injected, in-process log bridge. Runs entirely inside the built (debug) app and forwards its logs to
 * the IDE over an abstract-namespace {@link LocalSocket} — a device-local channel that needs no Android
 * permission (unlike reading another app's logcat, which requires the privileged {@code READ_LOGS}). The
 * IDE hosts the matching {@code LocalServerSocket} and renders what arrives in a live "Logcat" console tab.
 *
 * <p>Sources of log lines, in order of richness:
 * <ol>
 *   <li><b>logcat of this app's own PID</b> — captures every {@code android.util.Log.*} call. An app can read
 *       its OWN logs via {@code logcat} with no permission (since Android 4.1); this may still be blocked by
 *       SELinux on some devices, so it is best-effort.</li>
 *   <li><b>System.out / System.err</b> — tee'd so {@code println} output is forwarded even when logcat is
 *       unavailable (and de-duplicated against logcat, which also mirrors these).</li>
 *   <li><b>Uncaught exceptions</b> — a handler that forwards the crash stack trace, then delegates to the
 *       previously-installed handler so the app still crashes normally.</li>
 * </ol>
 *
 * <p><b>Wire protocol</b> (must stay in sync with the IDE-side reader in {@code :ide-android}
 * {@code AppLogChannelImpl}): a stream of length-prefixed frames — a 4-byte big-endian length, then that
 * many UTF-8 bytes. The payload is tab-separated with a leading kind field:
 * <pre>
 *   H \t &lt;protocolVersion&gt; \t &lt;packageName&gt; \t &lt;pid&gt; \t &lt;token&gt;              (one HELLO on connect)
 *   L \t &lt;timestampMs&gt; \t &lt;pid&gt; \t &lt;tid&gt; \t &lt;level&gt; \t &lt;tag&gt; \t &lt;message&gt;   (each LOG record)
 * </pre>
 * The {@code message} is the remainder after the sixth tab and may itself contain tabs and newlines.
 */
public final class IdeLogBridge {

    /** Abstract-namespace socket name the IDE listens on. Keep in sync with the IDE-side reader. */
    public static final String SOCKET_NAME = "dev.ide.codeassist.applog";
    /** Wire protocol version, bumped on any framing change. */
    public static final int PROTOCOL_VERSION = 1;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String SELF_TAG = "IdeLogBridge";
    /** logcat tags the tee/handler already forward — skip them from logcat to avoid duplicate records. */
    private static final String TAG_SYSTEM_OUT = "System.out";
    private static final String TAG_SYSTEM_ERR = "System.err";
    private static final String TAG_ANDROID_RUNTIME = "AndroidRuntime";

    /** Bounded so a flood while the IDE is not listening can never OOM the host app (drop-oldest). */
    private static final int QUEUE_CAPACITY = 4096;
    private static final int CONNECT_RETRY_MS = 500;

    private static volatile boolean started = false;

    private final String packageName;
    private final int pid;
    private final String token;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(QUEUE_CAPACITY);

    private IdeLogBridge(String packageName, int pid, String token) {
        this.packageName = packageName;
        this.pid = pid;
        this.token = token;
    }

    /** Boot the bridge once per process. Safe to call again (no-op after the first). */
    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        String pkg = context != null ? context.getPackageName() : "";
        // A per-run token can be supplied later (hardening); empty is fine — the IDE accepts an empty token.
        String tok = System.getProperty("dev.ide.applog.token", "");
        // Fully-qualified: java.lang.Process (the logcat subprocess) is used below, so we don't import android.os.Process.
        IdeLogBridge bridge = new IdeLogBridge(pkg != null ? pkg : "", android.os.Process.myPid(), tok != null ? tok : "");
        bridge.launch();
    }

    private void launch() {
        // The single writer: connects (with retry), then drains the queue to the socket, reconnecting on drop.
        Thread sender = new Thread(new Runnable() {
            @Override public void run() { runSender(); }
        }, "ide-applog-sender");
        sender.setDaemon(true);
        sender.start();

        // Crashes: always captured here (most important, most reliable), then delegated so the app still dies.
        installUncaughtHandler();
        // println output: tee'd so it survives even when logcat is blocked; logcat's own mirror of these is
        // filtered out below to avoid duplicates.
        installStdioTees();
        // android.util.Log.*: best-effort via reading our own PID's logcat.
        startLogcatReader();
    }

    // --- producers -------------------------------------------------------------------------------

    private void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    PrintStream ps = new PrintStream(bos, true, "UTF-8");
                    ps.print("FATAL EXCEPTION: " + t.getName() + "\n");
                    e.printStackTrace(ps);
                    ps.flush();
                    log(System.currentTimeMillis(), (int) t.getId(), 'E', TAG_ANDROID_RUNTIME, new String(bos.toByteArray(), UTF8));
                } catch (Throwable ignored) {
                } finally {
                    if (previous != null) previous.uncaughtException(t, e);
                }
            }
        });
    }

    private void installStdioTees() {
        System.setOut(new PrintStream(new LineForwardingStream(System.out, 'I', TAG_SYSTEM_OUT), true));
        System.setErr(new PrintStream(new LineForwardingStream(System.err, 'W', TAG_SYSTEM_ERR), true));
    }

    private void startLogcatReader() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runLogcat(); }
        }, "ide-applog-logcat");
        t.setDaemon(true);
        t.start();
    }

    private void runLogcat() {
        Process proc = null;
        try {
            // -v tag: "<priority>/<tag>: <message>". -T 1: show the most recent line then follow live (so we
            // start near "now" instead of dumping the whole circular buffer). Own-PID only (no READ_LOGS needed).
            proc = new ProcessBuilder("logcat", "-v", "tag", "-T", "1")
                .redirectErrorStream(true)
                .start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
            char lastLevel = 'I';
            String lastTag = "";
            String line;
            while ((line = reader.readLine()) != null) {
                char level = lastLevel;
                String tag = lastTag;
                String message = line;
                // Parse "P/tag: message"; a line that doesn't match is a continuation of the previous entry.
                if (line.length() > 2 && line.charAt(1) == '/') {
                    int colon = line.indexOf(':', 2);
                    if (colon > 2) {
                        level = line.charAt(0);
                        tag = line.substring(2, colon).trim();
                        message = line.length() > colon + 2 ? line.substring(colon + 2) : "";
                    }
                }
                lastLevel = level;
                lastTag = tag;
                // These are already forwarded by the stdio tees / the uncaught handler — don't duplicate them.
                if (SELF_TAG.equals(tag) || TAG_SYSTEM_OUT.equals(tag) || TAG_SYSTEM_ERR.equals(tag)
                    || TAG_ANDROID_RUNTIME.equals(tag)) continue;
                log(System.currentTimeMillis(), pid, level, tag, message);
            }
        } catch (Throwable ignored) {
            // exec denied (SELinux) or logcat unavailable — the stdio tees + crash handler still work.
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Enqueue a LOG record (drop-oldest when the queue is full so we never block the app or OOM it). */
    private void log(long timestampMs, int tid, char level, String tag, String message) {
        String safeTag = tag == null ? "" : tag.replace('\t', ' ');
        String payload = "L\t" + timestampMs + "\t" + pid + "\t" + tid + "\t" + level + "\t" + safeTag + "\t"
            + (message == null ? "" : message);
        if (!queue.offer(payload)) {
            queue.poll();
            queue.offer(payload);
        }
    }

    // --- the single socket writer ----------------------------------------------------------------

    private void runSender() {
        while (true) {
            LocalSocket socket = connect();
            if (socket == null) return; // gave up (bridge disabled for this run)
            try {
                OutputStream out = socket.getOutputStream();
                writeFrame(out, "H\t" + PROTOCOL_VERSION + "\t" + packageName + "\t" + pid + "\t" + token);
                out.flush();
                while (true) {
                    String payload = queue.poll(30, TimeUnit.SECONDS);
                    if (payload == null) continue; // keep the connection warm through idle periods
                    writeFrame(out, payload);
                    out.flush();
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                // Socket dropped (IDE closed the tab / went away). Loop and try to reconnect; queued records
                // keep buffering (bounded) so a later reconnect resumes the stream.
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private LocalSocket connect() {
        // Retry indefinitely but cheaply: the IDE opens its server socket before launching the app, so this
        // normally succeeds on the first try. A daemon thread parked here costs nothing if the IDE never listens.
        while (true) {
            try {
                LocalSocket socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                return socket;
            } catch (IOException e) {
                try {
                    Thread.sleep(CONNECT_RETRY_MS);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
    }

    private static void writeFrame(OutputStream out, String payload) throws IOException {
        byte[] bytes = payload.getBytes(UTF8);
        int len = bytes.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(bytes);
    }

    /**
     * An {@link OutputStream} that writes through to the original stream (so normal logging is unaffected) and
     * forwards each completed line to the bridge as a LOG record.
     */
    private final class LineForwardingStream extends OutputStream {
        private final OutputStream delegate;
        private final char level;
        private final String tag;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(128);

        LineForwardingStream(OutputStream delegate, char level, String tag) {
            this.delegate = delegate;
            this.level = level;
            this.tag = tag;
        }

        @Override public void write(int b) throws IOException {
            delegate.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override public void flush() throws IOException {
            delegate.flush();
        }

        private void flushLine() {
            if (line.size() == 0) return;
            String text = new String(line.toByteArray(), UTF8);
            line.reset();
            log(System.currentTimeMillis(), pid, level, tag, text);
        }
    }
}
