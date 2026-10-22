package com.tammem.htmlilaapk.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The key every made app is signed with. It is created once on the phone (RSA 2048 with a
 * self-signed certificate) and kept in the app's private folder, so a rebuilt app installs
 * as an update over the previous one.
 */
public final class SigningKeys {

    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    public SigningKeys(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public static synchronized SigningKeys loadOrCreate(File dir, String commonName)
            throws IOException, GeneralSecurityException {
        File keyFile = new File(dir, "key.pk8");
        File certFile = new File(dir, "cert.der");
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                return load(Files.readAllBytes(keyFile.toPath()), Files.readAllBytes(certFile.toPath()));
            } catch (Exception broken) {
                // fall through and make a new one
            }
        }
        SigningKeys k = create(commonName);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmpKey = new File(dir, "key.pk8.tmp");
        File tmpCert = new File(dir, "cert.der.tmp");
        Files.write(tmpKey.toPath(), k.privateKey.getEncoded());
        Files.write(tmpCert.toPath(), k.certificate.getEncoded());
        if (!tmpKey.renameTo(keyFile) || !tmpCert.renameTo(certFile)) {
            throw new IOException("Could not save the signing key");
        }
        return k;
    }

    public static SigningKeys load(byte[] pkcs8, byte[] certDer) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        return new SigningKeys(key, cert);
    }

    public static SigningKeys create(String commonName) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();

        byte[] sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid("2.5.4.3"), utf8(commonName))));
        Calendar from = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        from.add(Calendar.DAY_OF_YEAR, -1);
        Calendar to = (Calendar) from.clone();
        to.add(Calendar.YEAR, 40);
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);

        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
                integer(serial),
                sha256WithRsa,
                name,
                seq(time(from.getTime()), time(to.getTime())),
                name,
                pair.getPublic().getEncoded());

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pair.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();

        byte[] bitString = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bitString, 1, signature.length);
        byte[] der = seq(tbs, sha256WithRsa, tlv(0x03, bitString));

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new SigningKeys(pair.getPrivate(), cert);
    }

    // ------------------------------------------------------------------ DER

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xFF);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write((n >> 8) & 0xFF);
            out.write(n & 0xFF);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int k = tmp.length;
            tmp[--k] = (byte) (v & 0x7F);
            v >>= 7;
            while (v > 0) {
                tmp[--k] = (byte) (0x80 | (v & 0x7F));
                v >>= 7;
            }
            out.write(tmp, k, tmp.length - k);
        }
        return tlv(0x06, out.toByteArray());
    }

    /** UTCTime before 2050, GeneralizedTime after (RFC 5280). */
    static byte[] time(Date d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTime(d);
        boolean utc = c.get(Calendar.YEAR) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
