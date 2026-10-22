package com.tammem.htmlilaapk.engine;

import com.android.apksig.ApkSigner;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes a new, installable APK from the runner template: new package name, label,
 * version, icon and web files, then signs it (v1 + v2) with apksig.
 */
public final class ApkMaker {

    private static final Pattern PACKAGE =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    private static final long ZIP_TIME = 1230768000000L; // 2009-01-01, inside the DOS date range
    private static final String WWW = "assets/www/";
    private static final String CONFIG = "assets/app_config.json";

    /** What we know about template.apk (written by the build into template_info.properties). */
    public static final class Template {
        public final File apk;
        public final String packageName;
        public final String labelPlaceholder;
        public final String versionNamePlaceholder;
        public final String iconPath;

        public Template(File apk, Properties p) {
            this.apk = apk;
            this.packageName = req(p, "package");
            this.labelPlaceholder = req(p, "label");
            this.versionNamePlaceholder = req(p, "versionName");
            this.iconPath = req(p, "iconBg");
        }

        public static Template load(File apk, InputStream info) throws IOException {
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(info, StandardCharsets.UTF_8));
            return new Template(apk, p);
        }

        private static String req(Properties p, String key) {
            String v = p.getProperty(key);
            if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("template_info: " + key);
            return v.trim();
        }
    }

    /** The app to make. */
    public static final class Spec {
        public String label;
        public String packageName;
        public int versionCode = 1;
        public String versionName = "1.0";
        /** Contents of assets/app_config.json. */
        public String configJson = "{}";
        /** 432x432 PNG, used as the whole adaptive icon. Null keeps the template icon. */
        public byte[] iconPng;
        /** Folder with index.html and the rest of the site. Null keeps the template page. */
        public File wwwDir;
    }

    public interface Progress {
        void onStep(int step);
    }

    public static final int STEP_PACK = 1;
    public static final int STEP_SIGN = 2;
    public static final int STEP_DONE = 3;

    private ApkMaker() {
    }

    public static boolean isValidPackage(String name) {
        return name != null && PACKAGE.matcher(name).matches();
    }

    public static void build(Template t, Spec s, SigningKeys keys, File workDir, File out, Progress progress)
            throws Exception {
        if (s.label == null || s.label.trim().isEmpty()) throw new IllegalArgumentException("Empty app name");
        if (!isValidPackage(s.packageName)) throw new IllegalArgumentException("Bad package: " + s.packageName);
        if (s.wwwDir != null && !new File(s.wwwDir, "index.html").isFile()) {
            throw new IllegalArgumentException("index.html is missing");
        }
        if (progress != null) progress.onStep(STEP_PACK);

        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        File unsigned = new File(workDir, "unsigned.apk");
        writeUnsigned(t, s, unsigned);

        if (progress != null) progress.onStep(STEP_SIGN);
        File signedTmp = new File(out.getParentFile(), out.getName() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        signedTmp.delete();
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "CERT", keys.privateKey, Collections.singletonList(keys.certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(signedTmp)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setCreatedBy("AppMaker")
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        if (!signedTmp.renameTo(out)) throw new IOException("Could not move the APK into place");
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        if (progress != null) progress.onStep(STEP_DONE);
    }

    /** The patched manifest, also handy for tests. */
    public static byte[] patchManifest(Template t, Spec s, byte[] manifest) {
        Axml x = Axml.parse(manifest);
        Map<String, String> pkg = new HashMap<>();
        pkg.put(t.packageName, s.packageName);
        Map<String, String> label = new HashMap<>();
        label.put(t.labelPlaceholder, s.label.trim());
        Map<String, String> version = new HashMap<>();
        version.put(t.versionNamePlaceholder, s.versionName);
        if (x.replaceStrings(pkg) == 0) throw new IllegalStateException("Template package not found");
        if (x.replaceStrings(label) == 0) throw new IllegalStateException("Template label not found");
        x.replaceStrings(version);
        if (!x.setIntAttribute("manifest", "versionCode", s.versionCode)) {
            throw new IllegalStateException("versionCode not found");
        }
        return x.toBytes();
    }

    private static void writeUnsigned(Template t, Spec s, File dest) throws IOException {
        try (ZipFile template = new ZipFile(t.apk);
             FileOutputStream fos = new FileOutputStream(dest)) {
            CountingStream counter = new CountingStream(new BufferedOutputStream(fos, 1 << 16));
            ZipOutputStream zip = new ZipOutputStream(counter);

            ZipEntry manifestEntry = template.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) throw new IOException("Template has no manifest");
            byte[] manifest = patchManifest(t, s, readAll(template.getInputStream(manifestEntry)));
            put(zip, counter, "AndroidManifest.xml", manifest, false);

            Enumeration<? extends ZipEntry> entries = template.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.equals("AndroidManifest.xml") || isSignatureFile(name)) continue;
                if (name.equals(CONFIG)) continue;
                if (s.wwwDir != null && name.startsWith(WWW)) continue;
                if (s.iconPng != null && name.equals(t.iconPath)) continue;
                byte[] data = readAll(template.getInputStream(e));
                put(zip, counter, name, data, e.getMethod() == ZipEntry.STORED);
            }

            put(zip, counter, CONFIG, s.configJson.getBytes(StandardCharsets.UTF_8), false);
            if (s.iconPng != null) put(zip, counter, t.iconPath, s.iconPng, true);
            if (s.wwwDir != null) {
                List<File> files = new ArrayList<>();
                listFiles(s.wwwDir, files);
                String base = s.wwwDir.getCanonicalPath() + File.separator;
                for (File f : files) {
                    String rel = f.getCanonicalPath().substring(base.length()).replace(File.separatorChar, '/');
                    byte[] data;
                    try (InputStream in = new FileInputStream(f)) {
                        data = readAll(in);
                    }
                    put(zip, counter, WWW + rel, data, isAlreadyCompressed(rel));
                }
            }
            zip.finish();
            zip.flush();
            counter.flush();
        }
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    private static boolean isAlreadyCompressed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".mp3") || n.endsWith(".mp4") || n.endsWith(".ogg")
                || n.endsWith(".m4a") || n.endsWith(".webm") || n.endsWith(".woff2") || n.endsWith(".zip");
    }

    private static void listFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
        for (File c : sorted) {
            if (c.isDirectory()) listFiles(c, out);
            else if (c.isFile()) out.add(c);
        }
    }

    /** Writes one entry; stored entries get their data aligned to 4 bytes like zipalign does. */
    private static void put(ZipOutputStream zip, CountingStream counter, String name, byte[] data, boolean store)
            throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(ZIP_TIME);
        if (store) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
            zip.flush();
            long headerStart = counter.count;
            int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
            long dataStart = headerStart + 30 + nameLen + 6;
            int pad = (int) ((4 - (dataStart % 4)) % 4);
            byte[] extra = new byte[6 + pad];
            extra[0] = 0x35;               // 0xD935: Android alignment extra field
            extra[1] = (byte) 0xD9;
            extra[2] = (byte) (2 + pad);   // size of what follows
            extra[3] = 0;
            extra[4] = 4;                  // alignment
            extra[5] = 0;
            e.setExtra(extra);
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = input.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
