package com.unl0cker.hibernate.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * This app's own adb key: RSA 2048, kept in app-private storage (never backed up). The phone
 * trusts it once you choose "Always allow" for it; wireless debugging then accepts it too.
 */
public final class AdbKey {

    private static final String FILE = "adbkey.pk8";
    private static final String NAME = "hibernate@phone";
    private static final int BITS = 2048;

    /** DER prefix of a SHA-1 DigestInfo: adb signs the 20-byte token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static AdbKey instance;

    final PrivateKey privateKey;
    final PublicKey publicKey;
    final X509Certificate certificate;
    private final BigInteger modulus;
    private final BigInteger exponent;

    private AdbKey(PrivateKey privateKey) throws Exception {
        this.privateKey = privateKey;
        this.modulus = ((RSAPrivateKey) privateKey).getModulus();
        // Conscrypt's key class is not an RSAPrivateCrtKey; the key is always generated with F4.
        this.exponent = privateKey instanceof RSAPrivateCrtKey
                ? ((RSAPrivateCrtKey) privateKey).getPublicExponent() : RSAKeyGenParameterSpec.F4;
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(modulus, exponent));
        this.certificate = selfSigned();
    }

    public static synchronized AdbKey get(Context context) throws Exception {
        if (instance == null) {
            File file = new File(context.getNoBackupFilesDir(), FILE);
            if (!file.exists()) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(new RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4));
                byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
                File tmp = new File(file.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(encoded);
                }
                if (!tmp.renameTo(file)) {
                    throw new IllegalStateException("cannot save the adb key");
                }
            }
            byte[] encoded = Files.readAllBytes(file.toPath());
            instance = new AdbKey(KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded)));
        }
        return instance;
    }

    /** Signature for an AUTH token (legacy, non-TLS connections). */
    byte[] signToken(byte[] token) throws Exception {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(privateKey);
        signature.update(SHA1_DIGEST_INFO);
        signature.update(token);
        return signature.sign();
    }

    /** The public key in adb's format: base64 of android_pubkey_encode(), a name, then NUL. */
    byte[] adbPublicKey() {
        int words = BITS / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(modulus.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(BITS * 2).mod(modulus);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + BITS / 8 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(words);
        buf.putInt(n0inv.intValue());
        buf.put(littleEndian(modulus, BITS / 8));
        buf.put(littleEndian(rr, BITS / 8));
        buf.putInt(exponent.intValue());

        String text = Base64.encodeToString(buf.array(), Base64.NO_WRAP) + " " + NAME;
        byte[] chars = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[chars.length + 1];
        System.arraycopy(chars, 0, out, 0, chars.length);
        return out;
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        byte[] le = new byte[size];
        for (int i = 0; i < size && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    // --- Minimal self-signed X.509 certificate for the TLS client side of wireless debugging.
    // adbd only compares the public key inside it with the authorized keys.

    private X509Certificate selfSigned() throws Exception {
        byte[] sha256WithRsa = seq(oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b), new byte[] {0x05, 0x00});
        byte[] name = seq(set(seq(oid(0x55, 0x04, 0x03), tlv(0x0c, NAME.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(
                tlv(0x17, "250101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[] {0x02})),
                tlv(0x02, new byte[] {0x01}),
                sha256WithRsa,
                name,
                validity,
                name,
                publicKey.getEncoded());

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(tbs);
        byte[] sig = signature.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);

        byte[] der = seq(tbs, sha256WithRsa, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] oid(int... bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return tlv(0x06, out);
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
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
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        out.write(value, 0, len);
        return out.toByteArray();
    }
}
