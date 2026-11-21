package app.driftremote.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

import javax.net.ssl.SSLSocket;

/**
 * Pairing with a TV over TLS on port 6467 ("polo" protocol, protocol version 2).
 *
 * <ol>
 *   <li>{@link #start()} connects and runs the handshake; afterwards the TV shows a
 *   6-character code on screen.</li>
 *   <li>{@link #finish(String)} sends a hash of both certificates plus that code. When
 *   the TV accepts it, it trusts this phone's certificate on the remote port.</li>
 * </ol>
 */
public final class Pairing implements AutoCloseable {
    public static final int PORT = 6467;

    private static final int STATUS_OK = 200;
    private static final int ENCODING_HEX = 3;
    private static final int ROLE_INPUT = 1;

    private final String host;
    private final int port;
    private final Identity identity;
    private final String clientName;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;

    public Pairing(String host, int port, Identity identity, String clientName) {
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.clientName = clientName;
    }

    /** Connects and asks the TV to show a pairing code. Blocks for up to ~10 seconds. */
    public void start() throws IOException {
        try {
            socket = (SSLSocket) identity.sslContext().getSocketFactory().createSocket();
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        socket.startHandshake();
        in = socket.getInputStream();
        out = socket.getOutputStream();

        send(outer().msg(10, new Pb.W().str(1, "atvremote").str(2, clientName)));
        expect(11, "pairing request");

        Pb.W encoding = new Pb.W().varint(1, ENCODING_HEX).varint(2, 6);
        send(outer().msg(20, new Pb.W().msg(1, encoding).varint(3, ROLE_INPUT)));
        expect(20, "options");

        send(outer().msg(30, new Pb.W().msg(1, encoding).varint(2, ROLE_INPUT)));
        expect(31, "configuration");
        // The code is now on the TV screen. Waiting for the user may take a while.
        socket.setSoTimeout(0);
    }

    /** Sends the code the TV shows. Throws {@link WrongCodeException} if it doesn't match. */
    public void finish(String code) throws IOException {
        code = code.trim().toUpperCase(Locale.US);
        if (!code.matches("[0-9A-F]{6}")) throw new WrongCodeException("The code has 6 characters (0-9 and A-F).");
        byte[] secret = secret(identity.publicKey(), serverKey(), code);
        if ((secret[0] & 0xff) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new WrongCodeException("That code doesn't match. Check the code on the TV and try again.");
        }
        socket.setSoTimeout(10000);
        send(outer().msg(40, new Pb.W().bytes(1, secret)));
        expect(41, "code");
    }

    /** The pairing secret: SHA-256 over both RSA keys and the last 4 hex digits of the code. */
    public static byte[] secret(RSAPublicKey client, RSAPublicKey server, String code) {
        try {
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            h.update(unsigned(client.getModulus()));
            h.update(unsigned(client.getPublicExponent()));
            h.update(unsigned(server.getModulus()));
            h.update(unsigned(server.getPublicExponent()));
            h.update(hex(code.substring(2)));
            return h.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RSAPublicKey serverKey() throws IOException {
        Certificate[] certs = socket.getSession().getPeerCertificates();
        if (certs.length == 0 || !(((X509Certificate) certs[0]).getPublicKey() instanceof RSAPublicKey)) {
            throw new IOException("The TV uses an unsupported certificate type.");
        }
        return (RSAPublicKey) ((X509Certificate) certs[0]).getPublicKey();
    }

    private static Pb.W outer() {
        return new Pb.W().varint(1, 2).varint(2, STATUS_OK);
    }

    private void send(Pb.W msg) throws IOException {
        Pb.writeFrame(out, msg.toBytes());
    }

    /** Reads the TV's answer and checks that it is OK and carries the expected field. */
    private void expect(int field, String step) throws IOException {
        byte[] raw = Pb.readFrame(in);
        if (raw == null) throw new IOException("The TV closed the connection during the " + step + " step.");
        Pb.R r = new Pb.R(raw);
        int status = 0;
        boolean found = false;
        while (r.next()) {
            if (r.field == 2) status = r.int32();
            if (r.field == field) found = true;
        }
        if (status == 402) throw new WrongCodeException("The TV rejected the code. Try pairing again.");
        if (status != STATUS_OK) throw new IOException("The TV answered " + status + " during the " + step + " step.");
        if (!found) throw new IOException("Unexpected answer from the TV during the " + step + " step.");
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    public static final class WrongCodeException extends IOException {
        public WrongCodeException(String msg) {
            super(msg);
        }
    }
}
