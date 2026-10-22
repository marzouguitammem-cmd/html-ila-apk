package com.tammem.htmlilaapk.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.apksig.ApkVerifier;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs on the build machine after the runner template is built: makes a real APK
 * from it and checks the result. The CI then installs that APK on an emulator.
 */
public class ApkMakerTest {

    private static final File TEMPLATE = new File("src/main/assets/template.apk");
    private static final File INFO = new File("src/main/assets/template_info.properties");

    @Test
    public void makesASignedApkFromTheTemplate() throws Exception {
        Assume.assumeTrue("template.apk not prepared", TEMPLATE.isFile() && INFO.isFile());
        ApkMaker.Template t;
        try (InputStream in = new FileInputStream(INFO)) {
            t = ApkMaker.Template.load(TEMPLATE, in);
        }

        File work = new File("build/test-out");
        File www = new File(work, "www");
        File img = new File(www, "img");
        assertTrue(img.mkdirs() || img.isDirectory());
        String page = "<!doctype html><html lang=\"ar\" dir=\"rtl\"><head><title>تجربة</title>"
                + "<style>body{background:#1b5e20;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh}</style>"
                + "</head><body><h1>مرحبا من صانع التطبيقات</h1><p id=\"js\">JS ✖</p>"
                + "<script>document.getElementById('js').textContent='JS ✔ '+(1+2);"
                + "localStorage.setItem('k','v');</script></body></html>";
        Files.write(new File(www, "index.html").toPath(),
                CodeCleaner.prepareHtml(page).getBytes(StandardCharsets.UTF_8));
        byte[] icon = Files.readAllBytes(new File("../runner/src/main/res/drawable-xxxhdpi/app_icon_bg.png").toPath());
        Files.write(new File(img, "icon.png").toPath(), icon);

        ApkMaker.Spec s = new ApkMaker.Spec();
        s.label = "تطبيق التجربة";
        s.packageName = "com.tammem.apps.citest";
        s.versionCode = 3;
        s.versionName = "1.3";
        s.configJson = "{\"fullscreen\":false,\"orientation\":\"auto\",\"keepScreenOn\":false,\"startUrl\":\"\",\"bg\":\"#FFFFFF\"}";
        s.iconPng = icon;
        s.wwwDir = www;

        SigningKeys keys = SigningKeys.loadOrCreate(new File(work, "keys"), "AppMaker");
        File out = new File(work, "sample.apk");
        ApkMaker.build(t, s, keys, new File(work, "tmp"), out, null);
        assertTrue(out.length() > 1000);

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        assertTrue("signature: " + r.getErrors(), r.isVerified());
        assertTrue(r.isVerifiedUsingV2Scheme());

        try (ZipFile z = new ZipFile(out)) {
            ZipEntry m = z.getEntry("AndroidManifest.xml");
            Axml x = Axml.parse(ApkMaker.readAll(z.getInputStream(m)));
            assertTrue(x.strings().contains("com.tammem.apps.citest"));
            assertTrue(x.strings().contains("تطبيق التجربة"));
            assertTrue(!x.strings().contains(t.packageName));
            assertTrue(z.getEntry("assets/www/index.html") != null);
            assertTrue(z.getEntry("assets/www/img/icon.png") != null);
            assertEquals(ZipEntry.STORED, z.getEntry("resources.arsc").getMethod());
        }
    }

    @Test
    public void cleansPastedAnswers() {
        String answer = "Here you go:\n```html\n<!DOCTYPE html>\n<html><body>Hi</body></html>\n```\nEnjoy!";
        assertEquals("<!DOCTYPE html>\n<html><body>Hi</body></html>", CodeCleaner.extractCode(answer));
        assertEquals(CodeCleaner.HTML, CodeCleaner.kind("<div>x</div>"));
        assertEquals(CodeCleaner.REACT, CodeCleaner.kind("import React from 'react';\nexport default function A(){}"));
        assertTrue(CodeCleaner.prepareHtml("<html><head></head><body></body></html>").contains("viewport"));
    }
}
