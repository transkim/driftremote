package app.driftremote.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * This phone's identity towards the TV: an RSA key and a self-signed
 * certificate. The TV remembers the certificate after pairing, so the same
 * identity must be reused for every later connection.
 */
public final class Identity {
    public final PrivateKey privateKey;
    public final X509Certificate cert;

    private Identity(PrivateKey privateKey, X509Certificate cert) {
        this.privateKey = privateKey;
        this.cert = cert;
    }

    /** Creates a new identity. RSA key generation takes a second or two on a phone. */
    public static Identity create(String commonName) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();
        byte[] der = selfSignedCert(kp, commonName);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new Identity(kp.getPrivate(), cert);
    }

    public byte[] toBytes() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(1);
        byte[] key = privateKey.getEncoded();
        byte[] c = cert.getEncoded();
        out.writeInt(key.length);
        out.write(key);
        out.writeInt(c.length);
        out.write(c);
        out.flush();
        return bos.toByteArray();
    }

    public static Identity fromBytes(byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != 1) throw new IOException("unknown identity format");
        byte[] key = new byte[in.readInt()];
        in.readFully(key);
        byte[] c = new byte[in.readInt()];
        in.readFully(c);
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(c));
        return new Identity(pk, cert);
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) cert.getPublicKey();
    }

    /**
     * TLS context that presents this identity as the client certificate and
     * accepts the TV's self-signed certificate (the TV is identified by pairing,
     * not by a certificate authority).
     */
    public SSLContext sslContext() throws Exception {
        final X509Certificate[] chain = {cert};
        KeyManager km = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{"me"}; }
            @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return "me"; }
            @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return "me"; }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[]{"me"}; }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return "me"; }
            @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return "me"; }
            @Override public X509Certificate[] getCertificateChain(String alias) { return chain; }
            @Override public PrivateKey getPrivateKey(String alias) { return privateKey; }
        };
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }

    // ---- minimal DER writer for a self-signed X.509 v3 certificate ----

    static byte[] selfSignedCert(KeyPair kp, String cn) throws Exception {
        byte[] algId = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, cn.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 24L * 3600 * 1000)),
                utcTime(new Date(now + 10L * 365 * 24 * 3600 * 1000)));
        byte[] basicConstraints = seq(oid("2.5.29.19"),
                tlv(0x04, seq(new byte[]{0x01, 0x01, (byte) 0xff}, integer(BigInteger.ZERO))));
        byte[] san = seq(oid("2.5.29.17"),
                tlv(0x04, seq(tlv(0x82, cn.getBytes(StandardCharsets.US_ASCII)))));
        byte[] extensions = tlv(0xa3, seq(basicConstraints, san));
        byte[] tbs = seq(
                tlv(0xa0, integer(BigInteger.valueOf(2))),
                integer(new BigInteger(63, new SecureRandom())),
                algId, name, validity, name,
                kp.getPublic().getEncoded(),
                extensions);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign();
        byte[] bits = new byte[s.length + 1];
        System.arraycopy(s, 0, bits, 1, s.length);
        return seq(tbs, algId, tlv(0x03, bits));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int n = 0;
            byte[] tmp = new byte[10];
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write(len >> 8);
            out.write(len);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
