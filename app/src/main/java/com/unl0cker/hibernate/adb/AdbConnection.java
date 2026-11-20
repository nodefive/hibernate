package com.unl0cker.hibernate.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal adb client for the phone's own adbd on 127.0.0.1: connect (TLS for wireless
 * debugging, RSA token auth for "adb tcpip" mode) and run commands through the exec: service.
 */
public final class AdbConnection implements Closeable {

    /** The key is not (or no longer) authorized on this phone. */
    public static final class NotAuthorizedException extends IOException {
        NotAuthorizedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;

    private static final int VERSION = 0x01000001;
    private static final int STLS_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int IO_TIMEOUT_MS = 10_000;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private boolean prompted;

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private AdbConnection() {
    }

    /**
     * Connects to adbd on 127.0.0.1:port. With promptMs > 0, an unknown key is offered to the
     * phone, which shows "Allow USB debugging?"; the call waits up to promptMs for an answer.
     */
    public static AdbConnection open(AdbKey key, int port, int promptMs) throws Exception {
        AdbConnection c = new AdbConnection();
        try {
            c.handshake(key, port, promptMs);
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** True if the phone had to ask "Allow USB debugging?" for this connection. */
    public boolean prompted() {
        return prompted;
    }

    private void handshake(AdbKey key, int port, int promptMs) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(IO_TIMEOUT_MS);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));

        boolean sentSignature = false;
        boolean sentKey = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                throw new NotAuthorizedException(sentKey
                        ? "no answer to the authorization prompt" : "no answer from adb", e);
            } catch (SSLException e) {
                // TLS 1.3: a rejected client certificate shows up on the first read.
                throw new NotAuthorizedException("wireless debugging rejected the key", e);
            }
            switch (m.command) {
                case A_STLS:
                    send(A_STLS, STLS_VERSION, 0, new byte[0]);
                    upgradeToTls(key, port);
                    break;
                case A_AUTH:
                    if (m.arg0 != AUTH_TOKEN) {
                        throw new IOException("unexpected AUTH type " + m.arg0);
                    }
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, key.signToken(m.data));
                        sentSignature = true;
                    } else if (promptMs > 0 && !sentKey) {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey());
                        sentKey = true;
                        prompted = true;
                        socket.setSoTimeout(promptMs);
                    } else {
                        throw new NotAuthorizedException("key not authorized", null);
                    }
                    break;
                case A_CNXN:
                    socket.setSoTimeout(IO_TIMEOUT_MS);
                    return;
                default:
                    // Ignore anything else before the connection is up.
                    break;
            }
        }
    }

    private void upgradeToTls(AdbKey key, int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[] {new FixedKeyManager(key)},
                new TrustManager[] {new AnyServer()}, null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory()
                .createSocket(socket, "127.0.0.1", port, true);
        tls.setUseClientMode(true);
        tls.setEnabledProtocols(new String[] {"TLSv1.3"});
        try {
            tls.startHandshake();
        } catch (SSLException e) {
            throw new NotAuthorizedException("wireless debugging rejected the key", e);
        }
        socket = tls;
        in = new DataInputStream(tls.getInputStream());
        out = tls.getOutputStream();
    }

    /** Runs a command with the exec: service and returns its output. */
    public String exec(String command) throws IOException {
        return service("exec:" + command);
    }

    /** Opens any adb service (e.g. "usb:") and collects what it writes until it closes. */
    public String service(String name) throws IOException {
        int localId = nextLocalId++;
        int remoteId = 0;
        send(A_OPEN, localId, 0, (name + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SSLException e) {
                throw new NotAuthorizedException("wireless debugging rejected the key", e);
            }
            if (m.arg1 != localId) {
                continue;
            }
            switch (m.command) {
                case A_OKAY:
                    remoteId = m.arg0;
                    break;
                case A_WRTE:
                    remoteId = m.arg0;
                    output.write(m.data, 0, m.data.length);
                    send(A_OKAY, localId, remoteId, new byte[0]);
                    break;
                case A_CLSE:
                    return output.toString(StandardCharsets.UTF_8.name());
                default:
                    break;
            }
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (byte b : data) {
            checksum += b & 0xff;
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("bad adb packet length " + length);
        }
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** Always presents this app's key and certificate. */
    private static final class FixedKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "adb";
        private final AdbKey key;

        FixedKeyManager(AdbKey key) {
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {key.certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key.privateKey;
        }
    }

    /** adbd uses a throwaway self-signed certificate; the connection only ever goes to 127.0.0.1. */
    private static final class AnyServer implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
