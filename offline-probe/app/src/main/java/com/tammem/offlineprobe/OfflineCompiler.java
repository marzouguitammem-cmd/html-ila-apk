package com.tammem.offlineprobe;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Proves (or disproves) that a Java file can be compiled to a dex ON THE DEVICE,
 * fully offline, using ECJ (compiler) and D8 (dexer) loaded from bundled dex jars.
 *
 * It returns a human-readable report; MainActivity shows it and writes it to a file
 * the CI reads from the emulator.
 */
public class OfflineCompiler {

    public final StringBuilder log = new StringBuilder();
    public boolean ecjOk, d8Ok;

    private final Context ctx;
    private File work, toolDir, androidJar;

    public OfflineCompiler(Context ctx) {
        this.ctx = ctx;
    }

    private File logFile;

    private void line(String s) {
        log.append(s).append('\n');
        // Flush to disk immediately so a crash mid-compile still shows how far we got.
        try {
            if (logFile != null) {
                try (FileOutputStream o = new FileOutputStream(logFile)) { o.write(log.toString().getBytes("UTF-8")); }
            }
        } catch (Throwable ignored) {
        }
    }

    public void run() {
        try {
            File ext = ctx.getExternalFilesDir(null);
            logFile = new File(ext != null ? ext : ctx.getFilesDir(), "probe.log");
            work = new File(ctx.getFilesDir(), "probe");
            toolDir = new File(ctx.getFilesDir(), "toolchain");
            deleteRec(work);
            work.mkdirs();
            toolDir.mkdirs();
            line("start");

            File ecjJar = copyAsset("toolchain/ecj.dex.jar", new File(toolDir, "ecj.dex.jar"));
            File d8Jar = copyAsset("toolchain/d8.dex.jar", new File(toolDir, "d8.dex.jar"));
            androidJar = copyAsset("toolchain/android.jar", new File(toolDir, "android.jar"));
            line("tools: ecj=" + mb(ecjJar) + "MB d8=" + mb(d8Jar) + "MB android=" + mb(androidJar) + "MB");

            File srcDir = new File(work, "src/com/example");
            srcDir.mkdirs();
            File src = new File(srcDir, "Hello.java");
            write(src,
                "package com.example;\n" +
                "import android.app.Activity;\n" +
                "import android.os.Bundle;\n" +
                "import android.widget.TextView;\n" +
                "import java.util.List;\n" +
                "import java.util.ArrayList;\n" +
                "public class Hello extends Activity {\n" +
                "  @Override protected void onCreate(Bundle b){\n" +
                "    super.onCreate(b);\n" +
                "    List<String> xs = new ArrayList<>();\n" +
                "    xs.add(\"a\"); xs.removeIf(s -> s.isEmpty());\n" +   // lambda => needs desugaring
                "    Runnable r = () -> setTitle(\"ok \" + xs.size());\n" +
                "    r.run();\n" +
                "    TextView t = new TextView(this); t.setText(\"hi\"); setContentView(t);\n" +
                "  }\n" +
                "}\n");

            File classes = new File(work, "classes");
            classes.mkdirs();
            line("compiling with ECJ...");
            ecjOk = compileWithEcj(ecjJar, src.getParentFile().getParentFile().getParentFile(), classes);
            line("ECJ compiled: " + ecjOk + " (.class files: " + countClasses(classes) + ")");
            if (!ecjOk) return;

            File dexOut = new File(work, "dex");
            dexOut.mkdirs();
            line("dexing with D8...");
            d8Ok = dexWithD8(d8Jar, classes, dexOut);
            File dex = new File(dexOut, "classes.dex");
            line("D8 dexed: " + d8Ok + " (classes.dex: " + (dex.isFile() ? dex.length() + " bytes" : "MISSING") + ")");
        } catch (Throwable t) {
            line("EXCEPTION: " + t);
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line(sw.toString());
        }
    }

    // ---- ECJ ----

    private boolean compileWithEcj(File ecjJar, File srcRoot, File out) throws Exception {
        ClassLoader cl = dexLoader(ecjJar);
        Class<?> batch = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Class<?> progress = cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress");
        Method compile = batch.getMethod("compile", String.class, PrintWriter.class, PrintWriter.class, progress);

        // Collect the actual .java files (paths in filesDir have no spaces).
        List<Path> sources = new ArrayList<>();
        collectJava(srcRoot, sources);
        StringBuilder cmd = new StringBuilder();
        cmd.append("-bootclasspath ").append(androidJar.getAbsolutePath())
           .append(" -source 8 -target 8 -nowarn -d ").append(out.getAbsolutePath());
        for (Path p : sources) cmd.append(' ').append(p.toString());

        StringWriter sysOut = new StringWriter(), sysErr = new StringWriter();
        Object ok = compile.invoke(null, cmd.toString(), new PrintWriter(sysOut), new PrintWriter(sysErr), null);
        String err = sysErr.toString().trim();
        if (!err.isEmpty()) line("  ecj stderr: " + err.substring(0, Math.min(err.length(), 500)));
        return Boolean.TRUE.equals(ok) && countClasses(out) > 0;
    }

    private void collectJava(File dir, List<Path> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectJava(f, out);
            else if (f.getName().endsWith(".java")) out.add(f.toPath());
        }
    }

    // ---- D8 (programmatic API: it never calls System.exit) ----

    private boolean dexWithD8(File d8Jar, File classesDir, File out) throws Exception {
        ClassLoader cl = dexLoader(d8Jar);
        Class<?> d8 = cl.loadClass("com.android.tools.r8.D8");
        Class<?> cmdClass = cl.loadClass("com.android.tools.r8.D8Command");
        Class<?> builderClass = cl.loadClass("com.android.tools.r8.D8Command$Builder");
        Class<?> outputMode = cl.loadClass("com.android.tools.r8.OutputMode");

        Object builder = cmdClass.getMethod("builder").invoke(null);

        List<Path> programs = new ArrayList<>();
        collectClassFiles(classesDir, programs);
        Method addProgram = builderClass.getMethod("addProgramFiles", Path[].class);
        addProgram.invoke(builder, (Object) programs.toArray(new Path[0]));

        Method addLib = builderClass.getMethod("addLibraryFiles", Path[].class);
        addLib.invoke(builder, (Object) new Path[]{androidJar.toPath()});

        builderClass.getMethod("setMinApiLevel", int.class).invoke(builder, 26);

        Object dexIndexed = null;
        for (Object c : outputMode.getEnumConstants()) {
            if (c.toString().equals("DexIndexed")) { dexIndexed = c; break; }
        }
        builderClass.getMethod("setOutput", Path.class, outputMode).invoke(builder, out.toPath(), dexIndexed);

        Object command = builderClass.getMethod("build").invoke(builder);
        d8.getMethod("run", cmdClass).invoke(null, command);
        return new File(out, "classes.dex").isFile();
    }

    private void collectClassFiles(File dir, List<Path> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectClassFiles(f, out);
            else if (f.getName().endsWith(".class")) out.add(f.toPath());
        }
    }

    // ---- dex loading ----

    private ClassLoader dexLoader(File dexJar) throws Exception {
        // DexClassLoader (not InMemoryDexClassLoader) so the tool's bundled resources
        // — ECJ's messages.properties, META-INF/services — remain loadable from the jar.
        File opt = new File(ctx.getCodeCacheDir(), dexJar.getName() + ".opt");
        opt.mkdirs();
        // Android (API 26+) refuses to load a dex from a writable file, so make it read-only.
        dexJar.setWritable(false, false);
        dexJar.setReadOnly();
        line("  loading " + dexJar.getName() + " (writable=" + dexJar.canWrite() + ")");
        return new dalvik.system.DexClassLoader(
                dexJar.getAbsolutePath(), opt.getAbsolutePath(), null, getClass().getClassLoader());
    }

    // ---- helpers ----

    private static String q(String s) { return "\"" + s + "\""; }

    private int countClasses(File dir) {
        int[] n = {0};
        countClasses(dir, n);
        return n[0];
    }
    private void countClasses(File dir, int[] n) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) { if (f.isDirectory()) countClasses(f, n); else if (f.getName().endsWith(".class")) n[0]++; }
    }

    private File copyAsset(String name, File dest) throws Exception {
        // Overwrite even if a previous run left it read-only (dex jars get chmod'd).
        if (dest.exists()) {
            dest.setWritable(true, true);
            dest.delete();
        }
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(name); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return dest;
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16]; int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }
    private static long mb(File f) { return f.length() / (1024 * 1024); }
    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }
}
