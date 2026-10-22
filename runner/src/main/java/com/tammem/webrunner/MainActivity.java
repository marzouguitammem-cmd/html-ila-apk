package com.tammem.webrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The whole app made by صانع التطبيقات: one full-screen WebView that shows the
 * files under assets/www (served from https://appassets.androidplatform.net so
 * localStorage, fetch and modules behave like on a real site), or a website
 * when app_config.json has a startUrl.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String LOCAL_START = "https://" + HOST + "/index.html";
    static final int FILE_REQUEST = 41;

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean fullscreen;
    private String startUrl = "";
    private String startHost = "";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject cfg = readConfig();
        fullscreen = cfg.optBoolean("fullscreen", false);
        startUrl = cfg.optString("startUrl", "").trim();
        if (!startUrl.isEmpty()) {
            Uri u = Uri.parse(startUrl);
            startHost = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        }

        String orientation = cfg.optString("orientation", "auto");
        if ("portrait".equals(orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else if ("landscape".equals(orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        if (cfg.optBoolean("keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        int bg = parseColor(cfg.optString("bg", "#FFFFFF"), Color.WHITE);

        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyBarColors(bg, bg);
        applyFullscreen();

        setupWebView();
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(startUrl.isEmpty() ? LOCAL_START : startUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
        applyFullscreen();
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_REQUEST || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- web view

    @SuppressWarnings({"SetJavaScriptEnabled", "deprecation"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "__AppMaker");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveAsset(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectHelpers();
                readPageColors();
                handler.postDelayed(MainActivity.this::readPageColors, 1500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !startUrl.isEmpty()) showOfflinePage();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                hideSystemBars(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                FrameLayout box = new FrameLayout(MainActivity.this);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad / 2, pad, 0);
                box.addView(input);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                String js = "(function(){var u=" + JSONObject.quote(url) + ";"
                        + "var b=window.__amBlobs&&window.__amBlobs.get(u);"
                        + "(b?Promise.resolve(b):fetch(u).then(function(r){return r.blob();}))"
                        + ".then(function(b){var f=new FileReader();f.onload=function(){__AppMaker.save("
                        + JSONObject.quote(name) + ",b.type||" + JSONObject.quote(mimeType == null ? "" : mimeType)
                        + ",f.result);};f.readAsDataURL(b);})"
                        + ".catch(function(e){__AppMaker.fail(String(e));});})();";
                web.evaluateJavascript(js, null);
            } else if (url.startsWith("data:")) {
                saveDataUrl(name, mimeType, url);
            } else {
                openExternal(Uri.parse(url));
            }
        });
    }

    /** Serves assets/www/... for https://appassets.androidplatform.net/... */
    private WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !HOST.equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        if (path.endsWith("/")) path += "index.html";
        path = path.substring(1);
        if (path.contains("..")) return notFound();
        String mime = mimeFor(path);
        boolean text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
                || mime.contains("xml") || mime.equals("image/svg+xml");
        try {
            InputStream in = getAssets().open("www/" + path);
            WebResourceResponse r = new WebResourceResponse(mime, text ? "UTF-8" : null, in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(headers);
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    static String mimeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": case "oga": return "audio/ogg";
            case "m4a": return "audio/mp4";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "txt": return "text/plain";
            case "xml": return "text/xml";
            case "wasm": return "application/wasm";
            case "pdf": return "application/pdf";
        }
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    /** true = we handled it (do not load in the web view). */
    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals(HOST)) return false;
            if (!startHost.isEmpty() && sameSite(host, startHost)) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob")
                || scheme.equals("javascript")) {
            return false;
        }
        if (scheme.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                startActivity(intent);
            } catch (Exception e) {
                // ignore
            }
            return true;
        }
        openExternal(uri);
        return true;
    }

    private static boolean sameSite(String host, String start) {
        String a = host.startsWith("www.") ? host.substring(4) : host;
        String b = start.startsWith("www.") ? start.substring(4) : start;
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) {
            // nothing can open it
        }
    }

    private void showOfflinePage() {
        String html = "<!doctype html><html dir=\"rtl\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding:30vh 24px 0;color:#333\">"
                + "<div style=\"font-size:48px\">📶</div>"
                + "<p style=\"font-size:18px\">ما فماش إنترنت</p>"
                + "<a href=\"" + startUrl.replace("\"", "%22") + "\" style=\"display:inline-block;margin-top:12px;"
                + "padding:12px 28px;background:#1E88E5;color:#fff;border-radius:24px;text-decoration:none\">"
                + "عاود جرّب</a></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    /** Lets <a download> and blob/data downloads work, keeping the file name. */
    private void injectHelpers() {
        String js = "(function(){if(window.__amHooked)return;window.__amHooked=1;"
                + "var M=window.__amBlobs=new Map();var oc=URL.createObjectURL;"
                + "URL.createObjectURL=function(o){var u=oc.apply(URL,arguments);try{if(o instanceof Blob)M.set(u,o);}catch(e){}return u;};"
                + "function save(h,n){var b=M.get(h);(b?Promise.resolve(b):fetch(h).then(function(r){return r.blob();}))"
                + ".then(function(b){var f=new FileReader();f.onload=function(){__AppMaker.save(n||'',b.type||'',f.result);};f.readAsDataURL(b);})"
                + ".catch(function(e){__AppMaker.fail(String(e));});}"
                + "function grab(a){if(!a||!a.hasAttribute||!a.hasAttribute('download'))return false;var h=a.href||'';"
                + "if(h.indexOf('blob:')===0||h.indexOf('data:')===0){save(h,a.getAttribute('download'));return true;}return false;}"
                + "var ck=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(grab(this))return;return ck.apply(this,arguments);};"
                + "document.addEventListener('click',function(e){var t=e.target;var a=t&&t.closest?t.closest('a[download]'):null;if(a&&grab(a))e.preventDefault();},true);"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- colors

    /** Paints the status / navigation bars with the page's own colors. */
    private void readPageColors() {
        if (web == null) return;
        String js = "(function(){try{function c(v){if(!v)return '';var d=document.createElement('div');d.style.color=v;"
                + "(document.body||document.documentElement).appendChild(d);var r=getComputedStyle(d).color;d.remove();return r;}"
                + "var m=document.querySelector('meta[name=\"theme-color\"]');var t=m?c(m.getAttribute('content')):'';"
                + "var b=document.body?getComputedStyle(document.body).backgroundColor:'';"
                + "if(!b||b==='transparent'||b.replace(/ /g,'')==='rgba(0,0,0,0)')b=getComputedStyle(document.documentElement).backgroundColor;"
                + "return JSON.stringify({t:t,b:b});}catch(e){return '';}})()";
        web.evaluateJavascript(js, value -> {
            try {
                if (value == null || value.equals("null")) return;
                String json = new JSONArray("[" + value + "]").getString(0);
                if (json.isEmpty()) return;
                JSONObject o = new JSONObject(json);
                Integer body = cssColor(o.optString("b"));
                Integer theme = cssColor(o.optString("t"));
                if (body == null && theme == null) return;
                int top = theme != null ? theme : body;
                int bottom = body != null ? body : theme;
                root.setBackgroundColor(bottom);
                web.setBackgroundColor(bottom);
                applyBarColors(top, bottom);
            } catch (Exception ignored) {
                // keep current colors
            }
        });
    }

    /** "rgb(1, 2, 3)" / "rgba(1, 2, 3, 0.5)" -> color, or null if missing / transparent. */
    static Integer cssColor(String v) {
        if (v == null) return null;
        v = v.trim();
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (!v.startsWith("rgb") || open < 0 || close <= open) return null;
        String[] parts = v.substring(open + 1, close).replace("/", ",").split("[,\\s]+");
        try {
            int r = Math.round(Float.parseFloat(parts[0]));
            int g = Math.round(Float.parseFloat(parts[1]));
            int b = Math.round(Float.parseFloat(parts[2]));
            if (parts.length > 3) {
                float a = Float.parseFloat(parts[3].replace("%", ""));
                if (parts[3].endsWith("%")) a /= 100f;
                if (a < 0.5f) return null;
            }
            return Color.rgb(r, g, b);
        } catch (Exception e) {
            return null;
        }
    }

    static int parseColor(String s, int fallback) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isLight(int color) {
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return lum > 0.6;
    }

    @SuppressWarnings("deprecation")
    private void applyBarColors(int top, int bottom) {
        Window w = getWindow();
        w.setStatusBarColor(top);
        w.setNavigationBarColor(bottom);
        boolean lightTop = isLight(top);
        boolean lightBottom = isLight(bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                        (lightTop ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                                | (lightBottom ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0),
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility();
            flags = lightTop ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) {
                flags = lightBottom ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            d.setSystemUiVisibility(flags);
        }
    }

    // ---------------------------------------------------------------- full screen

    private void applyFullscreen() {
        hideSystemBars(fullscreen || customView != null);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(boolean hide) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (hide) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            View d = w.getDecorView();
            int keep = d.getSystemUiVisibility()
                    & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (hide) {
                d.setSystemUiVisibility(keep
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            } else {
                d.setSystemUiVisibility(keep);
            }
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        applyFullscreen();
    }

    // ---------------------------------------------------------------- downloads

    private void saveDataUrl(String name, String mime, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IOException("bad data url");
            String meta = dataUrl.substring(5, comma);
            String payload = dataUrl.substring(comma + 1);
            byte[] bytes;
            if (meta.contains(";base64")) {
                bytes = Base64.decode(payload, Base64.DEFAULT);
            } else {
                bytes = URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            }
            String type = meta.split(";")[0];
            if (type.isEmpty()) type = mime;
            saveBytes(name, type, bytes);
        } catch (Exception e) {
            toast("ما نجمتش نحفظ الملف");
        }
    }

    private void saveBytes(String name, String mime, byte[] bytes) throws IOException {
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        if (name == null || name.trim().isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = "file" + (ext != null ? "." + ext : "");
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("no stream");
                out.write(bytes);
            }
            toast("✔ تحفظ في Download: " + name);
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            toast("✔ تحفظ: " + f.getAbsolutePath());
        }
    }

    private void toast(String msg) {
        handler.post(() -> {
            Toast t = Toast.makeText(this, msg, Toast.LENGTH_LONG);
            t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 120);
            t.show();
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void save(String name, String mime, String dataUrl) {
            handler.post(() -> saveDataUrl(name, mime, dataUrl));
        }

        @JavascriptInterface
        public void fail(String error) {
            toast("ما نجمتش نحفظ الملف");
        }
    }

    // ---------------------------------------------------------------- config

    private JSONObject readConfig() {
        try (InputStream in = getAssets().open("app_config.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
