package app.driftremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayDeque;

/**
 * Finds Android TV / Google TV devices on the Wi-Fi. Their built-in remote service
 * announces itself over mDNS as _androidtvremote2._tcp.
 */
public class Discovery {
    public interface Listener {
        void onFound(String name, String host);

        void onLost(String name);
    }

    private static final String TYPE = "_androidtvremote2._tcp.";

    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;

    public Discovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.lock = wifi == null ? null : wifi.createMulticastLock("driftremote");
        this.listener = listener;
    }

    public void start() {
        if (discovery != null || nsd == null) return;
        if (lock != null) {
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { discovery = null; }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    toResolve.add(info);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                main.post(() -> listener.onLost(info.getServiceName()));
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    public void stop() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
            discovery = null;
        }
        toResolve.clear();
        if (lock != null && lock.isHeld()) lock.release();
    }

    // Older Android versions resolve only one service at a time.
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty() || discovery == null) return;
        resolving = true;
        NsdServiceInfo info = toResolve.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int err) {
                    main.post(() -> {
                        resolving = false;
                        if (err == NsdManager.FAILURE_ALREADY_ACTIVE) toResolve.add(i);
                        main.postDelayed(Discovery.this::resolveNext, 300);
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    InetAddress addr = i.getHost();
                    main.post(() -> {
                        resolving = false;
                        if (addr != null) listener.onFound(i.getServiceName(), addr.getHostAddress());
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }
}
