package app.driftremote;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import app.driftremote.core.Identity;

public class App extends Application {
    private static App instance;
    private Identity identity;
    private TvStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            saveReport("crash.txt", "Thread: " + t.getName() + "\n" + stack(e));
            if (previous != null) previous.uncaughtException(t, e);
        });
        store = new TvStore(this);
        // Make the key early, so it is ready by the time the user picks a TV.
        new Thread(() -> {
            try {
                identity();
            } catch (Exception ignored) {
            }
        }, "identity").start();
    }

    /** Records a problem that was handled, so it can be shared with the crash report. */
    public static void logProblem(Throwable e) {
        App a = instance;
        if (a != null) a.saveReport("problem.txt", stack(e));
    }

    static String stack(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private void saveReport(String file, String body) {
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), file))) {
            String head = "Driftremote " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE
                    + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n" + new java.util.Date() + "\n\n";
            out.write((head + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Returns and removes the saved crash report, or null when there is none. */
    public String takeCrashReport() {
        File f = new File(getFilesDir(), "crash.txt");
        if (!f.exists()) return null;
        try {
            String text = new String(readAll(f), java.nio.charset.StandardCharsets.UTF_8);
            File p = new File(getFilesDir(), "problem.txt");
            if (p.exists()) text += "\n\nEarlier problem:\n" + new String(readAll(p), java.nio.charset.StandardCharsets.UTF_8);
            return text;
        } catch (IOException e) {
            return null;
        } finally {
            f.delete();
        }
    }

    public static App get() {
        return instance;
    }

    public TvStore store() {
        return store;
    }

    /** This phone's certificate for talking to TVs. Call off the main thread: the first call creates a key. */
    public synchronized Identity identity() throws Exception {
        if (identity != null) return identity;
        File f = new File(getFilesDir(), "identity.bin");
        if (f.exists()) {
            try {
                identity = Identity.fromBytes(readAll(f));
                return identity;
            } catch (Exception e) {
                // Damaged file: make a new identity (TVs will need pairing again).
            }
        }
        identity = Identity.create("driftremote-" + Build.MODEL.replaceAll("[^A-Za-z0-9-]", ""));
        File tmp = new File(getFilesDir(), "identity.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(identity.toBytes());
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save identity");
        store.forgetAllPairings();
        return identity;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int got = 0;
            while (got < b.length) {
                int n = in.read(b, got, b.length - got);
                if (n < 0) throw new IOException("short read");
                got += n;
            }
        }
        return b;
    }

    /** A friendly name the TV shows for this phone in its list of remotes. */
    public static String phoneName() {
        String m = Build.MODEL == null ? "phone" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!maker.isEmpty() && !m.toLowerCase().startsWith(maker.toLowerCase())) {
            m = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + m;
        }
        return "Driftremote on " + m;
    }
}
