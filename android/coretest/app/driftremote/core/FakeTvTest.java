package app.driftremote.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Plays the TV side of pairing + remote, to check the phone-side protocol code. Run with plain java. */
public class FakeTvTest {
    static Identity tv;
    static volatile X509Certificate pairedClient;
    static volatile String shownCode;
    static final List<String> got = Collections.synchronizedList(new ArrayList<>());

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok - " + what);
    }

    public static void main(String[] a) throws Exception {
        try { realMain(); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }

    static void realMain() throws Exception {
        tv = Identity.create("FakeTV");
        SSLServerSocket pairSrv = server();
        SSLServerSocket remoteSrv = server();
        int pairPort = pairSrv.getLocalPort(), remotePort = remoteSrv.getLocalPort();
        new Thread(() -> servePairing(pairSrv)).start();
        new Thread(() -> serveRemote(remoteSrv)).start();

        Identity phone = Identity.fromBytes(Identity.create("driftremote").toBytes());

        // 1) unpaired phone is refused on the remote port
        try {
            new RemoteSession("127.0.0.1", remotePort, phone, new Nop()).run();
            check(false, "unpaired connect should fail");
        } catch (RemoteSession.NotPairedException e) {
            check(true, "unpaired phone gets NotPairedException");
        }

        // 2) wrong code is caught locally or by the TV
        try (Pairing p = new Pairing("127.0.0.1", pairPort, phone, "Test phone")) {
            p.start();
            String bad = shownCode.substring(0, 2) + (shownCode.charAt(2) == '0' ? "1" : "0") + shownCode.substring(3);
            try { p.finish(bad); check(false, "wrong code accepted"); }
            catch (Pairing.WrongCodeException e) { check(true, "wrong code rejected: " + e.getMessage()); }
        }

        // 3) right code pairs
        try (Pairing p = new Pairing("127.0.0.1", pairPort, phone, "Test phone")) {
            p.start();
            check(shownCode != null, "TV shows code " + shownCode);
            p.finish(shownCode.toLowerCase());
            check(pairedClient != null, "TV accepted pairing");
        }

        // 4) remote session works
        CountDownLatch ready = new CountDownLatch(1);
        final int[] vol = {-1};
        final boolean[] power = {false};
        RemoteSession[] holder = new RemoteSession[1];
        RemoteSession s = new RemoteSession("127.0.0.1", remotePort, phone, new Nop() {
            public void onPowerState(boolean on) { power[0] = on; ready.countDown(); }
            public void onVolume(int l, int m, boolean mu) { vol[0] = l; }
        });
        holder[0] = s;
        Thread t = new Thread(() -> { try { s.run(); } catch (IOException e) { System.out.println("session ended: " + e.getMessage()); } });
        t.start();
        check(ready.await(5, TimeUnit.SECONDS), "remote started");
        check(power[0], "TV reports power on");
        s.key(19, RemoteSession.SHORT);
        s.text("hello");
        s.openLink("https://www.youtube.com");
        Thread.sleep(1500);
        check(vol[0] == 12, "volume reported");
        check(got.contains("key 19 3"), "d-pad up received");
        check(got.contains("text hello"), "text received");
        check(got.contains("link https://www.youtube.com"), "app link received");
        check(got.contains("pong 77"), "ping answered");
        check(got.contains("features " + (2|4|32|64|512)), "features negotiated");
        s.close();
        t.join(3000);

        // 5) cross-check secret against the Python reference implementation (androidtvremote2 algorithm)
        Path dir = Files.createTempDirectory("drt");
        Files.write(dir.resolve("c.der"), phone.cert.getEncoded());
        Files.write(dir.resolve("s.der"), tv.cert.getEncoded());
        String code = "4F" + "9A2C";
        byte[] mine = Pairing.secret(phone.publicKey(), tv.publicKey(), code);
        String py = "from cryptography import x509\nimport hashlib,sys\n" +
            "ib=lambda v:v.to_bytes((v.bit_length()+7)//8,'big')\n" +
            "c=x509.load_der_x509_certificate(open(sys.argv[1],'rb').read()).public_key().public_numbers()\n" +
            "s=x509.load_der_x509_certificate(open(sys.argv[2],'rb').read()).public_key().public_numbers()\n" +
            "h=hashlib.sha256();[h.update(ib(x)) for x in (c.n,c.e,s.n,s.e)];h.update(bytes.fromhex(sys.argv[3][2:]));print(h.hexdigest())\n";
        Files.write(dir.resolve("x.py"), py.getBytes());
        Process pr = new ProcessBuilder("python3", "-I", dir.resolve("x.py").toString(), dir.resolve("c.der").toString(), dir.resolve("s.der").toString(), code).start();
        String pyHex = new BufferedReader(new InputStreamReader(pr.getInputStream())).readLine();
        StringBuilder sb = new StringBuilder(); for (byte b : mine) sb.append(String.format("%02x", b));
        check(sb.toString().equals(pyHex), "secret matches Python reference");
        System.out.println("ALL PASSED");
        System.exit(0);
    }

    static SSLServerSocket server() throws Exception {
        SSLServerSocket ss = (SSLServerSocket) tv.sslContext().getServerSocketFactory().createServerSocket(0);
        ss.setNeedClientAuth(true);
        return ss;
    }

    // tv.sslContext's key manager only implements client aliases; use a server-capable one
    static {
    }

    static Pb.W outer() { return new Pb.W().varint(1, 2).varint(2, 200); }

    static void servePairing(SSLServerSocket ss) {
        while (true) {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.startHandshake();
                InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
                X509Certificate client = (X509Certificate) s.getSession().getPeerCertificates()[0];
                byte[] m;
                while ((m = Pb.readFrame(in)) != null) {
                    Pb.R r = new Pb.R(m); int field = 0; byte[] secret = null;
                    while (r.next()) { if (r.field >= 10) { field = r.field; if (field == 40) { Pb.R q = r.sub(); q.next(); secret = q.bytes(); } } }
                    if (field == 10) Pb.writeFrame(out, outer().msg(11, new Pb.W().str(1, "FakeTV")).toBytes());
                    else if (field == 20) Pb.writeFrame(out, outer().msg(20, new Pb.W().varint(3, 1)).toBytes());
                    else if (field == 30) {
                        String tail = String.format("%04X", new Random().nextInt(65536));
                        byte[] h = Pairing.secret((RSAPublicKey) client.getPublicKey(), tv.publicKey(), "00" + tail);
                        shownCode = String.format("%02X", h[0] & 0xff) + tail;
                        Pb.writeFrame(out, outer().msg(31, new Pb.W()).toBytes());
                    } else if (field == 40) {
                        byte[] want = Pairing.secret((RSAPublicKey) client.getPublicKey(), tv.publicKey(), shownCode);
                        if (Arrays.equals(want, secret)) {
                            pairedClient = client;
                            Pb.writeFrame(out, outer().msg(41, new Pb.W().bytes(1, secret)).toBytes());
                        } else Pb.writeFrame(out, new Pb.W().varint(1, 2).varint(2, 402).toBytes());
                    }
                }
            } catch (Exception e) { /* next client */ }
        }
    }

    static void serveRemote(SSLServerSocket ss) {
        while (true) {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.startHandshake();
                X509Certificate client = (X509Certificate) s.getSession().getPeerCertificates()[0];
                if (pairedClient == null || !pairedClient.equals(client)) { s.close(); continue; }
                InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
                Pb.W dev = new Pb.W().str(1, "FakeModel").str(2, "Hisense").varint(3, 1).str(4, "1").str(5, "x").str(6, "1");
                Pb.writeFrame(out, new Pb.W().msg(1, new Pb.W().varint(1, 622).msg(2, dev)).toBytes());
                byte[] m;
                while ((m = Pb.readFrame(in)) != null) {
                    Pb.R r = new Pb.R(m);
                    while (r.next()) {
                        Pb.R q = r.sub();
                        switch (r.field) {
                            case 1: while (q.next()) if (q.field == 1) got.add("features " + q.int32());
                                Pb.writeFrame(out, new Pb.W().msg(2, new Pb.W().varint(1, 622)).toBytes()); break;
                            case 2:
                                Pb.writeFrame(out, new Pb.W().msg(40, new Pb.W().bool(1, true)).toBytes());
                                Pb.writeFrame(out, new Pb.W().msg(50, new Pb.W().varint(6, 100).varint(7, 12)).toBytes());
                                Pb.writeFrame(out, new Pb.W().msg(8, new Pb.W().varint(1, 77).varint(2, 1)).toBytes()); break;
                            case 9: while (q.next()) got.add("pong " + q.int32()); break;
                            case 10: { int k = 0, d = 0; while (q.next()) { if (q.field == 1) k = q.int32(); if (q.field == 2) d = q.int32(); } got.add("key " + k + " " + d); break; }
                            case 21: while (q.next()) if (q.field == 3) { Pb.R e = q.sub(); while (e.next()) if (e.field == 2) { Pb.R o = e.sub(); while (o.next()) if (o.field == 3) got.add("text " + o.str()); } } break;
                            case 90: q.next(); got.add("link " + q.str()); break;
                        }
                    }
                }
            } catch (Exception e) { }
        }
    }

    static class Nop implements RemoteSession.Listener {
        public void onReady(String v, String m) {}
        public void onPowerState(boolean on) {}
        public void onVolume(int l, int m, boolean mu) {}
        public void onCurrentApp(String p) {}
        public void onImeField(boolean a, String t) {}
    }
}
