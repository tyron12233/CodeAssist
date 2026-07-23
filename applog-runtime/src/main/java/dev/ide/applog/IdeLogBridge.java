package dev.ide.applog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The injected, in-process log bridge. Runs entirely inside the built (debug) app and forwards its logs to
 * the IDE over <b>Binder</b>. A raw {@code LocalSocket} can't be used: on modern Android SELinux denies one
 * untrusted app connecting to another's abstract/local socket ({@code avc: denied { connectto }}), and the
 * IDE and the built app are separate untrusted apps. Binder is the sanctioned cross-app channel. The IDE hosts
 * an exported service resolvable by the {@link #SINK_ACTION} intent; this bridge resolves + binds it and pushes
 * batches of log frames through {@link #TXN_SUBMIT}. The IDE renders what arrives in a live "Logcat" console tab.
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
 * <p><b>Wire payload</b> (must stay in sync with the IDE-side {@code AppLogWire} in {@code :ide-core}): each
 * submitted frame is a tab-separated string with a leading kind field:
 * <pre>
 *   H \t &lt;protocolVersion&gt; \t &lt;packageName&gt; \t &lt;pid&gt; \t &lt;token&gt;              (one HELLO per connection)
 *   L \t &lt;timestampMs&gt; \t &lt;pid&gt; \t &lt;tid&gt; \t &lt;level&gt; \t &lt;tag&gt; \t &lt;message&gt;   (each LOG record)
 * </pre>
 * The {@code message} is the remainder after the sixth tab and may itself contain tabs and newlines. Frames are
 * carried as a {@code String[]} in the Binder transaction (no length-prefix framing) rather than a byte stream.
 */
public final class IdeLogBridge {

    // --- transport (Binder) — keep in sync with ide-core AppLogWire -------------------------------
    /** Intent action the IDE's exported log-sink service is resolvable by. */
    public static final String SINK_ACTION = "dev.ide.applog.SINK";
    /** Interface token written into the submit transaction's Parcel. */
    public static final String BINDER_DESCRIPTOR = "dev.ide.applog.IAppLogSink";
    /** Submit transaction code ({@code IBinder.FIRST_CALL_TRANSACTION}): a batch of wire payloads. */
    public static final int TXN_SUBMIT = 1;
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
    /** Max frames coalesced into one submit transaction (keeps each Binder payload well under the 1 MiB limit). */
    private static final int SUBMIT_BATCH_MAX = 256;
    /** How long the sender waits for the IDE to bind before giving up (so it never spins when the IDE is absent). */
    private static final long BIND_WAIT_MS = 8000L;

    private static volatile boolean started = false;

    private final String packageName;
    private final int pid;
    private final String token;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(QUEUE_CAPACITY);

    private Context appContext;
    private final Object bindLock = new Object();
    /** The IDE's sink binder once bound; null before bind / after the binding drops. */
    private volatile IBinder sink;

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
        bridge.appContext = context != null ? context.getApplicationContext() : null;
        bridge.launch();
    }

    private void launch() {
        // Bind to the IDE's sink service (async; onServiceConnected wakes the sender). No socket, no retry spin.
        bindToIde();
        // The single writer: waits for the binding, then drains the queue to the IDE, reconnecting on drop.
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

    /** Resolve the IDE's exported sink service by [SINK_ACTION] and bind to it. A missing/invisible IDE (the
     *  service isn't installed, or package visibility wasn't granted) simply leaves the bridge unbound — no
     *  retries, no log spam. BIND_AUTO_CREATE lets the system re-deliver onServiceConnected after a drop. */
    private void bindToIde() {
        if (appContext == null) return;
        try {
            Intent intent = new Intent(SINK_ACTION);
            ResolveInfo ri = appContext.getPackageManager().resolveService(intent, 0);
            if (ri == null || ri.serviceInfo == null) return; // IDE not installed / not visible to this app
            intent.setComponent(new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name));
            ServiceConnection conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    synchronized (bindLock) { sink = binder; bindLock.notifyAll(); }
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    synchronized (bindLock) { sink = null; }
                }
            };
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) {
            // Can't bind (service gone, security, older-API quirk) — the bridge stays dark, the app is unaffected.
        }
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

    // --- the single Binder writer ----------------------------------------------------------------

    private void runSender() {
        IBinder binder = awaitSink();
        if (binder == null) return; // IDE never bound (not installed / not visible / blocked) — give up quietly
        boolean helloSent = false;
        List<String> batch = new ArrayList<String>();
        while (true) {
            try {
                IBinder b = sink;
                if (b == null) {
                    // Binding dropped — wait (bounded) for BIND_AUTO_CREATE to re-deliver it, then resend HELLO.
                    b = awaitSink();
                    if (b == null) return;
                    helloSent = false;
                }
                if (!helloSent) {
                    submit(b, new String[]{ hello() });
                    helloSent = true;
                }
                String first = queue.poll(30, TimeUnit.SECONDS);
                if (first == null) continue; // idle — keep the loop alive
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, SUBMIT_BATCH_MAX - 1);
                submit(b, batch.toArray(new String[batch.size()]));
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // The binding died mid-submit (DeadObjectException / RemoteException). Drop it and loop: the
                // outer awaitSink waits for a reconnect (or gives up after BIND_WAIT_MS).
                sink = null;
            }
        }
    }

    /** Block (up to [BIND_WAIT_MS]) until the IDE's sink binder is available, or null if it never binds. */
    private IBinder awaitSink() {
        synchronized (bindLock) {
            long deadline = System.currentTimeMillis() + BIND_WAIT_MS;
            while (sink == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    bindLock.wait(remaining);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return sink;
        }
    }

    /** The one-time HELLO frame identifying this app to the IDE (which gates on the launched package). */
    private String hello() {
        return "H\t" + PROTOCOL_VERSION + "\t" + packageName + "\t" + pid + "\t" + token;
    }

    /** Submit a batch of wire-payload frames to the IDE over Binder (oneway — never blocks the app). */
    private void submit(IBinder binder, String[] frames) throws Exception {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(BINDER_DESCRIPTOR);
            data.writeStringArray(frames);
            binder.transact(TXN_SUBMIT, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
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

        @Override public void write(int b) throws java.io.IOException {
            delegate.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override public void flush() throws java.io.IOException {
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
