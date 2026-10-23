package com.tammem.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * معرض الصور: يبدا بالألبومات (الدوسيات اللي في التليفون)، تدخل لألبوم تشوف صورو،
 * وتحلّ صورة بشاشة كاملة: تكبير بإصبعين، تنقّل بالسحب، مشاركة، معلومات، حذف.
 * ملف Java واحد، الواجهة بالكود، بلا مكتبات.
 */
public class Gallery extends Activity {

    // ---------------------------------------------------------------- colors
    private static final int BG = Color.parseColor("#0D0F13");
    private static final int CARD = Color.parseColor("#171A21");
    private static final int CHIP = Color.parseColor("#232833");
    private static final int ACCENT = Color.parseColor("#4C8DFF");
    private static final int TEXT = Color.parseColor("#F2F5FA");
    private static final int MUTED = Color.parseColor("#8E98A9");

    private static final int REQ_PERM = 21;
    private static final int REQ_DELETE = 22;
    private static final String ALL_ID = "__all__";

    /** One photo on the phone. */
    static final class Photo {
        long id;
        String name;
        String bucketId;
        String bucket;
        long dateMs;
        long size;
        int width, height, orientation;
        Uri uri;
    }

    /** A folder (album) and its photos, newest first. */
    static final class Album {
        String id;
        String name;
        final List<Photo> photos = new ArrayList<>();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private LruCache<Long, Bitmap> thumbs;

    private final List<Album> albums = new ArrayList<>();
    private Album current;
    private int viewerIndex;
    private int viewerToken;
    private Photo pendingDelete;

    // screens
    private FrameLayout root;
    private LinearLayout albumsScreen, gridScreen;
    private FrameLayout viewerScreen;

    // albums screen
    private TextView totalText, stateText;
    private Button stateButton;
    private LinearLayout stateBox;
    private GridView albumGrid;
    private AlbumAdapter albumAdapter;

    // grid screen
    private TextView gridTitle, gridCount;
    private GridView photoGrid;
    private PhotoAdapter photoAdapter;

    // viewer
    private PhotoView photoView;
    private LinearLayout viewerTop, viewerBottom;
    private TextView viewerTitle, viewerCounter;
    private boolean viewerBars = true;

    private ContentObserver observer;

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int mem = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        thumbs = new LruCache<Long, Bitmap>(mem) {
            @Override protected int sizeOf(Long k, Bitmap v) { return v.getByteCount() / 1024; }
        };
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildAlbumsScreen();
        buildGridScreen();
        buildViewer();
        root.addView(albumsScreen, match());
        root.addView(gridScreen, match());
        root.addView(viewerScreen, match());
        gridScreen.setVisibility(View.GONE);
        viewerScreen.setVisibility(View.GONE);
        setContentView(root);
        applyColumns();

        if (hasPermission()) {
            load();
        } else {
            showState("باش نوريك صورك مقسومة على الدوسيات، لازم تعطيني الإذن 🖼️", "اعطي الإذن");
            requestPermissions(neededPermissions(), REQ_PERM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermission() && albums.isEmpty()) load();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyColumns();
    }

    @Override
    protected void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (viewerScreen.getVisibility() == View.VISIBLE) {
            closeViewer();
        } else if (gridScreen.getVisibility() == View.VISIBLE) {
            showAlbums();
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================= permission

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (hasPermission()) {
            load();
        } else if (!shouldShowRequestPermissionRationale(neededPermissions()[0])) {
            showState("الإذن مسكّر. حلّ الإعدادات ← الأذونات ← «الصور والفيديو» واختار «سماح».", "حلّ الإعدادات");
        } else {
            showState("ما نجمش نوريك الصور من غير الإذن.", "اعطي الإذن");
        }
    }

    private void onStateButton() {
        if (hasPermission()) { load(); return; }
        if (stateButton.getText().toString().contains("الإعدادات")) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } else {
            requestPermissions(neededPermissions(), REQ_PERM);
        }
    }

    // ================================================================= loading

    private void load() {
        if (albums.isEmpty()) showState("نجمّع في الصور…", null);
        io.execute(() -> {
            final List<Album> found = groupIntoAlbums(queryPhotos());
            ui.post(() -> applyAlbums(found));
        });
    }

    private void applyAlbums(List<Album> found) {
        String keepAlbum = current == null ? null : current.id;
        albums.clear();
        albums.addAll(found);
        totalText.setText(albums.isEmpty() ? "" : (albums.get(0).photos.size() + " صورة • " + (albums.size() - 1) + " دوسي"));
        albumAdapter.notifyDataSetChanged();
        if (albums.isEmpty()) {
            showState("ما لقيت حتى صورة في التليفون 🤷", null);
        } else {
            stateBox.setVisibility(View.GONE);
            albumGrid.setVisibility(View.VISIBLE);
        }
        if (keepAlbum != null) {
            Album again = null;
            for (Album a : albums) if (a.id.equals(keepAlbum)) again = a;
            if (again == null) {
                showAlbums();
            } else {
                current = again;
                gridCount.setText(current.photos.size() + " صورة");
                photoAdapter.notifyDataSetChanged();
            }
        }
        watchChanges();
    }

    private List<Photo> queryPhotos() {
        List<Photo> out = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.ORIENTATION,
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(collection, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c == null) return out;
            while (c.moveToNext()) {
                Photo p = new Photo();
                p.id = c.getLong(0);
                p.name = c.isNull(1) ? "صورة" : c.getString(1);
                p.bucketId = c.isNull(2) ? "0" : c.getString(2);
                p.bucket = c.isNull(3) ? "أخرى" : c.getString(3);
                long taken = c.isNull(4) ? 0 : c.getLong(4);
                long added = c.isNull(5) ? 0 : c.getLong(5) * 1000L;
                p.dateMs = taken > 0 ? taken : added;
                p.size = c.isNull(6) ? 0 : c.getLong(6);
                p.width = c.isNull(7) ? 0 : c.getInt(7);
                p.height = c.isNull(8) ? 0 : c.getInt(8);
                p.orientation = c.isNull(9) ? 0 : c.getInt(9);
                p.uri = ContentUris.withAppendedId(collection, p.id);
                out.add(p);
            }
        } catch (Exception e) {
            ui.post(() -> toast("ما نجمتش نقرا الصور"));
        } finally {
            if (c != null) c.close();
        }
        Collections.sort(out, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return out;
    }

    /** "All photos" first, then folders with the newest photos first. */
    private List<Album> groupIntoAlbums(List<Photo> photos) {
        List<Album> out = new ArrayList<>();
        if (photos.isEmpty()) return out;
        Album all = new Album();
        all.id = ALL_ID;
        all.name = "كل الصور";
        all.photos.addAll(photos);
        Map<String, Album> byId = new LinkedHashMap<>();
        for (Photo p : photos) {
            Album a = byId.get(p.bucketId);
            if (a == null) {
                a = new Album();
                a.id = p.bucketId;
                a.name = p.bucket;
                byId.put(p.bucketId, a);
            }
            a.photos.add(p);
        }
        out.add(all);
        out.addAll(byId.values());
        return out;
    }

    private void watchChanges() {
        if (observer != null) return;
        observer = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) {
                ui.removeCallbacks(reload);
                ui.postDelayed(reload, 1200);
            }
        };
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    private final Runnable reload = () -> {
        if (viewerScreen.getVisibility() != View.VISIBLE) load();
    };

    // ================================================================= albums screen

    private void buildAlbumsScreen() {
        albumsScreen = new LinearLayout(this);
        albumsScreen.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(16), dp(18), dp(10));
        TextView title = text("🖼️ الألبومات", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        totalText = text("", 13, MUTED);
        header.addView(title);
        header.addView(totalText);
        albumsScreen.addView(header);

        FrameLayout body = new FrameLayout(this);
        albumGrid = new GridView(this);
        albumGrid.setHorizontalSpacing(dp(12));
        albumGrid.setVerticalSpacing(dp(16));
        albumGrid.setPadding(dp(14), dp(4), dp(14), dp(20));
        albumGrid.setClipToPadding(false);
        albumGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        albumGrid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        albumAdapter = new AlbumAdapter();
        albumGrid.setAdapter(albumAdapter);
        albumGrid.setOnItemClickListener((p, v, pos, id) -> openAlbum(albums.get(pos)));
        body.addView(albumGrid, match());

        stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(dp(32), 0, dp(32), dp(60));
        stateText = text("", 16, MUTED);
        stateText.setGravity(Gravity.CENTER);
        stateText.setLineSpacing(dp(4), 1f);
        stateBox.addView(stateText);
        stateButton = pill("", ACCENT);
        stateButton.setOnClickListener(v -> onStateButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
        bp.topMargin = dp(18);
        stateBox.addView(stateButton, bp);
        body.addView(stateBox, match());
        albumsScreen.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showState(String message, String button) {
        albumGrid.setVisibility(View.GONE);
        stateBox.setVisibility(View.VISIBLE);
        stateText.setText(message);
        stateButton.setVisibility(button == null ? View.GONE : View.VISIBLE);
        if (button != null) stateButton.setText(button);
    }

    private int albumCols = 2, photoCols = 3, albumCell, photoCell;

    private void applyColumns() {
        int width = getResources().getDisplayMetrics().widthPixels;
        float widthDp = width / getResources().getDisplayMetrics().density;
        albumCols = Math.max(2, (int) (widthDp / 170));
        photoCols = Math.max(3, (int) (widthDp / 115));
        albumCell = (width - dp(28) - dp(12) * (albumCols - 1)) / albumCols;
        photoCell = (width - dp(4) - dp(3) * (photoCols - 1)) / photoCols;
        albumGrid.setNumColumns(albumCols);
        photoGrid.setNumColumns(photoCols);
        albumAdapter.notifyDataSetChanged();
        photoAdapter.notifyDataSetChanged();
    }

    private final class AlbumAdapter extends BaseAdapter {
        @Override public int getCount() { return albums.size(); }
        @Override public Object getItem(int i) { return albums.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            AlbumCell cell;
            if (convert == null) {
                cell = new AlbumCell();
                convert = cell.build();
                convert.setTag(cell);
            } else {
                cell = (AlbumCell) convert.getTag();
            }
            cell.cover.setLayoutParams(new LinearLayout.LayoutParams(albumCell, albumCell));
            Album a = albums.get(pos);
            cell.name.setText(a.id.equals(ALL_ID) ? "⭐ " + a.name : a.name);
            cell.count.setText(a.photos.size() + " صورة");
            convert.setContentDescription("album " + a.name);
            loadThumbInto(cell.cover, a.photos.get(0));
            return convert;
        }
    }

    private final class AlbumCell {
        ImageView cover;
        TextView name, count;

        View build() {
            LinearLayout box = new LinearLayout(Gallery.this);
            box.setOrientation(LinearLayout.VERTICAL);
            cover = new ImageView(Gallery.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(rounded(CARD, 18));
            cover.setClipToOutline(true);
            box.addView(cover);
            name = text("", 15, TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(4), dp(8), dp(4), 0);
            count = text("", 12, MUTED);
            count.setPadding(dp(4), 0, dp(4), 0);
            box.addView(name);
            box.addView(count);
            box.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        }
    }

    // ================================================================= grid screen

    private void buildGridScreen() {
        gridScreen = new LinearLayout(this);
        gridScreen.setOrientation(LinearLayout.VERTICAL);
        gridScreen.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(10), dp(16), dp(10));
        Button back = pill(isRtl() ? "→" : "←", Color.TRANSPARENT);
        back.setTextSize(22);
        back.setOnClickListener(v -> showAlbums());
        header.addView(back, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        gridTitle = text("", 20, TEXT);
        gridTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gridTitle.setSingleLine(true);
        gridTitle.setEllipsize(TextUtils.TruncateAt.END);
        gridCount = text("", 13, MUTED);
        titles.addView(gridTitle);
        titles.addView(gridCount);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gridScreen.addView(header);

        photoGrid = new GridView(this);
        photoGrid.setHorizontalSpacing(dp(3));
        photoGrid.setVerticalSpacing(dp(3));
        photoGrid.setPadding(dp(2), 0, dp(2), dp(12));
        photoGrid.setClipToPadding(false);
        photoGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        photoGrid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        photoAdapter = new PhotoAdapter();
        photoGrid.setAdapter(photoAdapter);
        photoGrid.setOnItemClickListener((p, v, pos, id) -> openViewer(pos));
        gridScreen.addView(photoGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void openAlbum(Album a) {
        current = a;
        gridTitle.setText(a.name);
        gridCount.setText(a.photos.size() + " صورة");
        photoAdapter.notifyDataSetChanged();
        photoGrid.setSelection(0);
        albumsScreen.setVisibility(View.GONE);
        gridScreen.setVisibility(View.VISIBLE);
    }

    private void showAlbums() {
        current = null;
        gridScreen.setVisibility(View.GONE);
        viewerScreen.setVisibility(View.GONE);
        albumsScreen.setVisibility(View.VISIBLE);
        albumAdapter.notifyDataSetChanged();
    }

    private final class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return current == null ? 0 : current.photos.size(); }
        @Override public Object getItem(int i) { return current.photos.get(i); }
        @Override public long getItemId(int i) { return current.photos.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv;
            if (convert == null) {
                iv = new ImageView(Gallery.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(CARD);
            } else {
                iv = (ImageView) convert;
            }
            iv.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoCell));
            Photo p = current.photos.get(pos);
            iv.setContentDescription("photo " + p.name);
            loadThumbInto(iv, p);
            return iv;
        }
    }

    // ================================================================= thumbnails

    private void loadThumbInto(final ImageView iv, final Photo p) {
        iv.setTag(p.id);
        Bitmap cached = thumbs.get(p.id);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        iv.setImageBitmap(null);
        io.execute(() -> {
            final Bitmap bmp = loadThumb(p);
            if (bmp == null) return;
            thumbs.put(p.id, bmp);
            ui.post(() -> { if (Long.valueOf(p.id).equals(iv.getTag())) iv.setImageBitmap(bmp); });
        });
    }

    @SuppressWarnings("deprecation")
    private Bitmap loadThumb(Photo p) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(p.uri, new Size(360, 360), null);
            }
            Bitmap b = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), p.id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
            return rotate(b, p.orientation);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================= viewer

    private void buildViewer() {
        viewerScreen = new FrameLayout(this);
        viewerScreen.setBackgroundColor(Color.BLACK);
        photoView = new PhotoView(this);
        photoView.setListener(new PhotoView.Listener() {
            @Override public void onSwipe(int direction) { showPhotoAt(viewerIndex + direction, direction); }
            @Override public void onTap() { setViewerBars(!viewerBars); }
        });
        viewerScreen.addView(photoView, match());

        viewerTop = new LinearLayout(this);
        viewerTop.setGravity(Gravity.CENTER_VERTICAL);
        viewerTop.setPadding(dp(6), dp(10), dp(14), dp(14));
        viewerTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#CC000000"), Color.TRANSPARENT}));
        Button back = pill(isRtl() ? "→" : "←", Color.TRANSPARENT);
        back.setTextSize(22);
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(v -> closeViewer());
        viewerTop.addView(back, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        viewerTitle = text("", 15, Color.WHITE);
        viewerTitle.setSingleLine(true);
        viewerTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        viewerCounter = text("", 12, Color.parseColor("#C9D1DD"));
        titles.addView(viewerTitle);
        titles.addView(viewerCounter);
        viewerTop.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        viewerScreen.addView(viewerTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        viewerBottom = new LinearLayout(this);
        viewerBottom.setGravity(Gravity.CENTER);
        viewerBottom.setPadding(dp(8), dp(24), dp(8), dp(18));
        viewerBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#CC000000"), Color.TRANSPARENT}));
        String[][] actions = {{"📤", "مشاركة"}, {"🖼️", "تعيين كـ"}, {"ℹ️", "معلومات"}, {"🗑️", "حذف"}};
        for (int i = 0; i < actions.length; i++) {
            final int which = i;
            LinearLayout a = new LinearLayout(this);
            a.setOrientation(LinearLayout.VERTICAL);
            a.setGravity(Gravity.CENTER);
            a.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView icon = text(actions[i][0], 22, Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            TextView label = text(actions[i][1], 12, Color.WHITE);
            label.setGravity(Gravity.CENTER);
            a.addView(icon);
            a.addView(label);
            a.setOnClickListener(v -> onViewerAction(which));
            viewerBottom.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        viewerScreen.addView(viewerBottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private void openViewer(int pos) {
        viewerScreen.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setViewerBars(true);
        showPhotoAt(pos, 0);
    }

    private void closeViewer() {
        viewerToken++;
        viewerScreen.setVisibility(View.GONE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        photoView.setImageBitmap(null);
        if (current != null) photoGrid.setSelection(Math.max(0, viewerIndex - photoCols));
    }

    private void showPhotoAt(int pos, int direction) {
        if (current == null || current.photos.isEmpty()) { closeViewer(); return; }
        if (pos < 0 || pos >= current.photos.size()) {
            toast(pos < 0 ? "هذي أوّل صورة" : "هذي آخر صورة");
            return;
        }
        viewerIndex = pos;
        final Photo p = current.photos.get(pos);
        viewerTitle.setText(p.name);
        viewerCounter.setText((pos + 1) + " / " + current.photos.size() + "  •  "
                + DateFormat.getMediumDateFormat(this).format(p.dateMs));
        Bitmap quick = thumbs.get(p.id);
        photoView.setPhoto(quick);
        if (direction != 0) {
            photoView.setTranslationX(direction * (isRtl() ? -1 : 1) * dp(60));
            photoView.setAlpha(0.3f);
            photoView.animate().translationX(0).alpha(1f).setDuration(180).start();
        }
        final int token = ++viewerToken;
        io.execute(() -> {
            final Bitmap full = decodeFull(p);
            ui.post(() -> { if (token == viewerToken && full != null) photoView.setPhoto(full); });
        });
    }

    private void setViewerBars(boolean show) {
        viewerBars = show;
        viewerTop.animate().alpha(show ? 1f : 0f).setDuration(180).start();
        viewerBottom.animate().alpha(show ? 1f : 0f).setDuration(180).start();
        viewerTop.setClickable(show);
        for (int i = 0; i < viewerBottom.getChildCount(); i++) viewerBottom.getChildAt(i).setClickable(show);
        viewerTop.getChildAt(0).setClickable(show);
    }

    private Bitmap decodeFull(Photo p) {
        int max = Math.min(4096, Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                final int maxSide = max;
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), p.uri);
                return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    int longest = Math.max(w, h);
                    if (longest > maxSide) {
                        float sc = maxSide / (float) longest;
                        decoder.setTargetSize(Math.max(1, (int) (w * sc)), Math.max(1, (int) (h * sc)));
                    }
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(p.uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > max) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(p.uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
            return rotate(b, p.orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap rotate(Bitmap b, int degrees) {
        if (b == null || degrees % 360 == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private void onViewerAction(int which) {
        if (current == null || viewerIndex >= current.photos.size()) return;
        Photo p = current.photos.get(viewerIndex);
        if (which == 0) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/*");
            send.putExtra(Intent.EXTRA_STREAM, p.uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "مشاركة الصورة"));
        } else if (which == 1) {
            Intent set = new Intent(Intent.ACTION_ATTACH_DATA);
            set.setDataAndType(p.uri, "image/*");
            set.putExtra("mimeType", "image/*");
            set.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(set, "تعيين الصورة كـ"));
            } catch (Exception e) {
                toast("ما فماش تطبيق ينجم يعمل هذا");
            }
        } else if (which == 2) {
            showInfo(p);
        } else {
            askDelete(p);
        }
    }

    private void showInfo(Photo p) {
        String res = (p.width > 0 && p.height > 0) ? (p.width + " × " + p.height) : "—";
        String msg = "📄 " + p.name
                + "\n📁 " + p.bucket
                + "\n📅 " + DateFormat.getMediumDateFormat(this).format(p.dateMs) + "  "
                + DateFormat.getTimeFormat(this).format(p.dateMs)
                + "\n📐 " + res
                + "\n💾 " + sizeText(p.size);
        new AlertDialog.Builder(this).setTitle("معلومات الصورة").setMessage(msg)
                .setPositiveButton("باهي", null).show();
    }

    private void askDelete(Photo p) {
        if (Build.VERSION.SDK_INT < 30) {
            toast("الحذف يخدم من أندرويد 11 وفوق");
            return;
        }
        try {
            List<Uri> uris = new ArrayList<>();
            uris.add(p.uri);
            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
            pendingDelete = p;
            startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
        } catch (Exception e) {
            toast("ما نجمتش نحذف الصورة");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DELETE) return;
        Photo p = pendingDelete;
        pendingDelete = null;
        if (res != RESULT_OK || p == null) return;
        for (Album a : albums) a.photos.remove(p);
        toast("تحذفت 🗑️");
        if (current == null || current.photos.isEmpty()) {
            closeViewer();
            showAlbums();
            load();
            return;
        }
        photoAdapter.notifyDataSetChanged();
        gridCount.setText(current.photos.size() + " صورة");
        showPhotoAt(Math.min(viewerIndex, current.photos.size() - 1), 0);
    }

    // ================================================================= zoomable photo

    /** ImageView with pinch zoom, double-tap zoom, panning, and swipe to change photo. */
    static final class PhotoView extends ImageView {
        interface Listener {
            void onSwipe(int direction);
            void onTap();
        }

        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private float fitScale = 1f;
        private Listener listener;
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        PhotoView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float target = clamp(scale() * d.getScaleFactor(), fitScale, fitScale * 6f);
                    float f = target / scale();
                    matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                    fixBounds();
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }

                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (listener != null) listener.onTap();
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (zoomed()) {
                        fit();
                    } else {
                        float f = 2.5f;
                        matrix.postScale(f, f, e.getX(), e.getY());
                        fixBounds();
                    }
                    return true;
                }

                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (!zoomed()) return false;
                    matrix.postTranslate(-dx, -dy);
                    fixBounds();
                    return true;
                }

                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (zoomed() || listener == null) return false;
                    if (Math.abs(vx) > Math.abs(vy) * 1.3f && Math.abs(vx) > 700) {
                        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                        int dir = vx < 0 ? 1 : -1;
                        listener.onSwipe(rtl ? -dir : dir);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setListener(Listener l) { listener = l; }

        void setPhoto(Bitmap b) {
            setImageBitmap(b);
            post(this::fit);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }

        private void fit() {
            if (getDrawable() == null || getWidth() == 0) return;
            float bw = getDrawable().getIntrinsicWidth(), bh = getDrawable().getIntrinsicHeight();
            if (bw <= 0 || bh <= 0) return;
            fitScale = Math.min(getWidth() / bw, getHeight() / bh);
            matrix.reset();
            matrix.postScale(fitScale, fitScale);
            matrix.postTranslate((getWidth() - bw * fitScale) / 2f, (getHeight() - bh * fitScale) / 2f);
            setImageMatrix(matrix);
        }

        private float scale() {
            matrix.getValues(values);
            return values[Matrix.MSCALE_X];
        }

        private boolean zoomed() { return scale() > fitScale * 1.02f; }

        /** Keeps the photo centred when smaller than the screen and edge-locked when larger. */
        private void fixBounds() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            matrix.mapRect(r);
            float dx = 0, dy = 0, w = getWidth(), h = getHeight();
            if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < w) dx = w - r.right;
            if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < h) dy = h - r.bottom;
            matrix.postTranslate(dx, dy);
            setImageMatrix(matrix);
        }

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    // ================================================================= helpers

    private boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button pill(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(16);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(rounded(color, 25));
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
