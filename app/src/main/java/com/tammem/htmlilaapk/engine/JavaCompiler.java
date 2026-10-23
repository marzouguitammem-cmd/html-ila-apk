package com.tammem.htmlilaapk.engine;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Compiles Java source to a classes.dex ON THE DEVICE, fully offline, using ECJ
 * (compiler) and D8 (dexer) loaded from bundled dex jars. Proven on Android ART.
 *
 * Notes that made this work on Android:
 *  - ECJ is picked so it never references java.lang.Runtime$Version (absent on ART).
 *  - The JDK 8 java.compiler API is bundled (its SourceVersion doesn't call Runtime.version()).
 *  - -proc:none skips annotation processing (which needs newer SourceVersion constants).
 *  - A java.lang.invoke stub is on the bootclasspath so lambdas compile.
 *  - dex jars are made read-only before loading (Android refuses writable dex).
 */
public final class JavaCompiler {

    public static final class CompileException extends Exception {
        public CompileException(String message) { super(message); }
    }

    private final Context ctx;
    private final File toolDir;
    private File ecjJar, d8Jar, androidJar, lambdaStub;

    public JavaCompiler(Context ctx) {
        this.ctx = ctx;
        this.toolDir = new File(ctx.getFilesDir(), "jtoolchain");
    }

    /** True if the toolchain assets are present in this build. */
    public boolean isAvailable() {
        try {
            String[] names = ctx.getAssets().list("toolchain");
            if (names == null) return false;
            boolean ecj = false, d8 = false, aj = false;
            for (String n : names) {
                if (n.equals("ecj.dex.jar")) ecj = true;
                if (n.equals("d8.dex.jar")) d8 = true;
                if (n.equals("android.jar")) aj = true;
            }
            return ecj && d8 && aj;
        } catch (Exception e) {
            return false;
        }
    }

    public interface Progress { void onStep(String step); }

    /**
     * Compiles the given sources (relative path -> content) and returns classes.dex bytes.
     * Throws CompileException with the compiler's own message on a code error.
     */
    public byte[] compileToDex(java.util.Map<String, String> sources, Progress progress) throws Exception {
        prepareToolchain(progress);
        File work = new File(ctx.getCacheDir(), "jbuild");
        deleteRec(work);
        File srcRoot = new File(work, "src");
        File classes = new File(work, "classes");
        File dexOut = new File(work, "dex");
        srcRoot.mkdirs(); classes.mkdirs(); dexOut.mkdirs();

        boolean hasSource = false;
        for (java.util.Map.Entry<String, String> e : sources.entrySet()) {
            String rel = e.getKey().replace('\\', '/').replaceFirst("^/+", "");
            if (rel.contains("..") || !(rel.endsWith(".java"))) continue;
            File f = new File(srcRoot, rel);
            f.getParentFile().mkdirs();
            write(f, e.getValue());
            hasSource = true;
        }
        if (!hasSource) throw new CompileException("ما فماش ملف Java صالح");

        if (progress != null) progress.onStep("compile");
        compileWithEcj(srcRoot, classes);

        if (progress != null) progress.onStep("dex");
        dexWithD8(classes, dexOut);
        File dex = new File(dexOut, "classes.dex");
        if (!dex.isFile()) throw new CompileException("ما تكوّنش classes.dex");
        return ApkMaker.readAll(new java.io.FileInputStream(dex));
    }

    // ------------------------------------------------------------------ toolchain

    private synchronized void prepareToolchain(Progress progress) throws Exception {
        if (progress != null) progress.onStep("prepare");
        toolDir.mkdirs();
        ecjJar = ensureAsset("toolchain/ecj.dex.jar", "ecj.dex.jar");
        d8Jar = ensureAsset("toolchain/d8.dex.jar", "d8.dex.jar");
        androidJar = ensureAsset("toolchain/android.jar", "android.jar");
        lambdaStub = ensureAsset("toolchain/lambdastub.jar", "lambdastub.jar");
    }

    /** Copies an asset to the tool dir once (by size), leaving it read-only. */
    private File ensureAsset(String assetPath, String name) throws Exception {
        File dest = new File(toolDir, name);
        long assetLen = assetLength(assetPath);
        if (dest.isFile() && dest.length() == assetLen) {
            dest.setWritable(false, false);
            return dest;
        }
        if (dest.exists()) { dest.setWritable(true, true); dest.delete(); }
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(assetPath); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        // Android refuses to load a writable dex; the jars are read-only once copied.
        dest.setWritable(false, false);
        dest.setReadOnly();
        return dest;
    }

    private long assetLength(String assetPath) {
        try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(assetPath)) {
            return fd.getLength();
        } catch (Exception e) {
            // Compressed assets can't openFd; fall back to streaming length.
            try (InputStream in = ctx.getAssets().open(assetPath)) {
                long n = 0; byte[] b = new byte[1 << 16]; int r;
                while ((r = in.read(b)) > 0) n += r;
                return n;
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    // ------------------------------------------------------------------ ECJ

    private void compileWithEcj(File srcRoot, File out) throws Exception {
        ClassLoader cl = dexLoader(ecjJar);
        Class<?> batch = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Class<?> progress = cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress");
        Method compile = batch.getMethod("compile", String.class, PrintWriter.class, PrintWriter.class, progress);

        List<Path> sources = new ArrayList<>();
        collect(srcRoot, ".java", sources);
        StringBuilder cmd = new StringBuilder();
        cmd.append("-bootclasspath ").append(androidJar.getAbsolutePath())
           .append(File.pathSeparator).append(lambdaStub.getAbsolutePath())
           .append(" -source 8 -target 8 -proc:none -nowarn -d ").append(out.getAbsolutePath());
        for (Path p : sources) cmd.append(' ').append(p.toString());

        StringWriter sysErr = new StringWriter();
        Object ok = compile.invoke(null, cmd.toString(), new PrintWriter(new StringWriter()), new PrintWriter(sysErr), null);
        if (!Boolean.TRUE.equals(ok) || count(out, ".class") == 0) {
            String err = sysErr.toString().trim();
            throw new CompileException(cleanEcjErrors(err, srcRoot));
        }
    }

    /** Turns ECJ's verbose output into a short, readable error for the user. */
    private String cleanEcjErrors(String raw, File srcRoot) {
        if (raw.isEmpty()) return "فمّا غلط في الكود";
        String prefix = srcRoot.getAbsolutePath() + "/";
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String l : lines) {
            l = l.replace(prefix, "").trim();
            if (l.isEmpty() || l.equals("----------")) continue;
            sb.append(l).append('\n');
            if (l.startsWith("ERROR") || l.contains("cannot") || (++shown > 14)) {
                if (shown > 14) break;
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? raw.substring(0, Math.min(raw.length(), 600)) : s.substring(0, Math.min(s.length(), 900));
    }

    // ------------------------------------------------------------------ D8

    private void dexWithD8(File classesDir, File out) throws Exception {
        ClassLoader cl = dexLoader(d8Jar);
        Class<?> d8 = cl.loadClass("com.android.tools.r8.D8");
        Class<?> cmdClass = cl.loadClass("com.android.tools.r8.D8Command");
        Class<?> builderClass = cl.loadClass("com.android.tools.r8.D8Command$Builder");
        Class<?> outputMode = cl.loadClass("com.android.tools.r8.OutputMode");

        Object builder = cmdClass.getMethod("builder").invoke(null);
        List<Path> programs = new ArrayList<>();
        collect(classesDir, ".class", programs);
        builderClass.getMethod("addProgramFiles", Path[].class).invoke(builder, (Object) programs.toArray(new Path[0]));
        builderClass.getMethod("addLibraryFiles", Path[].class).invoke(builder, (Object) new Path[]{androidJar.toPath()});
        builderClass.getMethod("setMinApiLevel", int.class).invoke(builder, 26);
        Object dexIndexed = null;
        for (Object c : outputMode.getEnumConstants()) if (c.toString().equals("DexIndexed")) dexIndexed = c;
        builderClass.getMethod("setOutput", Path.class, outputMode).invoke(builder, out.toPath(), dexIndexed);
        Object command = builderClass.getMethod("build").invoke(builder);
        d8.getMethod("run", cmdClass).invoke(null, command);
    }

    // ------------------------------------------------------------------ helpers

    private ClassLoader dexLoader(File dexJar) {
        File opt = new File(ctx.getCodeCacheDir(), dexJar.getName() + ".opt");
        opt.mkdirs();
        dexJar.setWritable(false, false);
        return new DexClassLoader(dexJar.getAbsolutePath(), opt.getAbsolutePath(), null, getClass().getClassLoader());
    }

    private static void collect(File dir, String ext, List<Path> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, ext, out);
            else if (f.getName().endsWith(ext)) out.add(f.toPath());
        }
    }

    private static int count(File dir, String ext) {
        int[] n = {0};
        countRec(dir, ext, n);
        return n[0];
    }
    private static void countRec(File dir, String ext, int[] n) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) { if (f.isDirectory()) countRec(f, ext, n); else if (f.getName().endsWith(ext)) n[0]++; }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }
}
