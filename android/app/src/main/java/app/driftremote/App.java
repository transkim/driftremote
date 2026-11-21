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
        store = new TvStore(this);
        // Make the key early, so it is ready by the time the user picks a TV.
        new Thread(() -> {
            try {
                identity();
            } catch (Exception ignored) {
            }
        }, "identity").start();
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
