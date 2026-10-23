package com.tammem.vplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * مشغّل فيديو: يطلب الإذن، يعرض الفيديوهات الكل اللي في التليفون (صور مصغّرة،
 * دوسيات، بحث، ترتيب)، ويشغّلهم. ملف Java واحد، الواجهة بالكود، بلا مكتبات.
 */
public class VideoPlayer extends Activity {

    // ---------------------------------------------------------------- colors
    private static final int BG = Color.parseColor("#0E1116");
    private static final int CARD = Color.parseColor("#171B22");
    private static final int CHIP = Color.parseColor("#232934");
    private static final int ACCENT = Color.parseColor("#3D7BFF");
    private static final int TEXT = Color.parseColor("#F1F4F9");
    private static final int MUTED = Color.parseColor("#8F99AA");

    private static final int REQ_PERM = 11;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String[] SORT_NAMES = {"الأحدث", "الاسم", "الأكبر", "الأطول"};

    /** One video on the phone. */
    static final class Video {
        long id;
        String name;
        String folder;
        long durationMs;
        long size;
        long dateAdded;
        Uri uri;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private LruCache<Long, Bitmap> thumbs;
    private SharedPreferences prefs;

    private final List<Video> all = new ArrayList<>();
    private final List<Video> shown = new ArrayList<>();
    private String folderFilter = null;
    private String query = "";
    private int sortMode = 0;

    // library views
    private FrameLayout root;
    private LinearLayout library;
    private TextView countText, stateText;
    private Button stateButton, sortBtn;
    private LinearLayout stateBox, chipRow;
    private EditText searchField;
    private ListView list;
    private VideoAdapter adapter;

    // player views
    private FrameLayout player;
    private VideoView video;
    private MediaPlayer mediaPlayer;
    private LinearLayout topOverlay, bottomOverlay;
    private TextView playerTitle, timeText, centerHint;
    private Button playBtn, speedBtn;
    private SeekBar seek;
    private int current = -1;
    private int speedIndex = 2;
    private boolean fullscreen, userSeeking, prepared, overlayShown = true;

    private ContentObserver observer;

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int mem = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        thumbs = new LruCache<Long, Bitmap>(mem) {
            @Override protected int sizeOf(Long k, Bitmap v) { return v.getByteCount() / 1024; }
        };
        prefs = getSharedPreferences("positions", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildLibrary();
        buildPlayer();
        root.addView(library, match());
        root.addView(player, match());
        player.setVisibility(View.GONE);
        setContentView(root);

        if (hasPermission()) {
            loadVideos();
        } else {
            showState("باش نوريك الفيديوهات اللي في تليفونك، لازم تعطيني الإذن 🎬", "اعطي الإذن");
            requestPermissions(neededPermissions(), REQ_PERM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermission() && all.isEmpty() && player.getVisibility() != View.VISIBLE) loadVideos();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player.getVisibility() == View.VISIBLE && prepared) {
            savePosition();
            video.pause();
            updatePlayButton();
        }
    }

    @Override
    protected void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        video.stopPlayback();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (player.getVisibility() == View.VISIBLE) {
            closePlayer();
        } else if (searchField.getVisibility() == View.VISIBLE) {
            toggleSearch();
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================= permission

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
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
            loadVideos();
        } else if (!shouldShowRequestPermissionRationale(neededPermissions()[0])) {
            showState("الإذن مسكّر. حلّ الإعدادات ← الأذونات ← «الصور والفيديو» واختار «سماح».", "حلّ الإعدادات");
        } else {
            showState("ما نجمش نوريك الفيديوهات من غير الإذن.", "اعطي الإذن");
        }
    }

    private void onStateButton() {
        if (hasPermission()) { loadVideos(); return; }
        if (stateButton.getText().toString().contains("الإعدادات")) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } else {
            requestPermissions(neededPermissions(), REQ_PERM);
        }
    }

    // ================================================================= loading

    private void loadVideos() {
        showState("نلوّج على الفيديوهات…", null);
        io.execute(() -> {
            final List<Video> found = queryVideos();
            ui.post(() -> {
                all.clear();
                all.addAll(found);
                buildChips();
                applyFilter();
                watchChanges();
            });
        });
    }

    private List<Video> queryVideos() {
        List<Video> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(collection, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c == null) return out;
            while (c.moveToNext()) {
                Video v = new Video();
                v.id = c.getLong(0);
                v.name = c.isNull(1) ? "فيديو" : c.getString(1);
                v.durationMs = c.isNull(2) ? 0 : c.getLong(2);
                v.size = c.isNull(3) ? 0 : c.getLong(3);
                v.folder = c.isNull(4) ? "أخرى" : c.getString(4);
                v.dateAdded = c.isNull(5) ? 0 : c.getLong(5);
                v.uri = ContentUris.withAppendedId(collection, v.id);
                out.add(v);
            }
        } catch (Exception e) {
            ui.post(() -> toast("ما نجمتش نقرا الفيديوهات"));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Refresh the list by itself when videos are added or deleted. */
    private void watchChanges() {
        if (observer != null) return;
        observer = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) {
                ui.removeCallbacks(reload);
                ui.postDelayed(reload, 1200);
            }
        };
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    private final Runnable reload = () -> {
        if (player.getVisibility() != View.VISIBLE) {
            io.execute(() -> {
                final List<Video> found = queryVideos();
                ui.post(() -> { all.clear(); all.addAll(found); buildChips(); applyFilter(); });
            });
        }
    };

    private void applyFilter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Video v : all) {
            if (folderFilter != null && !folderFilter.equals(v.folder)) continue;
            if (!q.isEmpty() && !v.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(v);
        }
        Comparator<Video> cmp;
        if (sortMode == 1) cmp = (a, b) -> a.name.compareToIgnoreCase(b.name);
        else if (sortMode == 2) cmp = (a, b) -> Long.compare(b.size, a.size);
        else if (sortMode == 3) cmp = (a, b) -> Long.compare(b.durationMs, a.durationMs);
        else cmp = (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
        Collections.sort(shown, cmp);
        adapter.notifyDataSetChanged();

        countText.setText(all.size() + " فيديو");
        if (all.isEmpty()) {
            showState("ما لقيت حتى فيديو في التليفون 🤷", null);
        } else if (shown.isEmpty()) {
            showState("ما فماش نتيجة", null);
        } else {
            stateBox.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    // ================================================================= library UI

    private void buildLibrary() {
        library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(10), dp(6));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("🎬 فيديوهاتي", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        countText = text("", 13, MUTED);
        titles.addView(title);
        titles.addView(countText);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button searchBtn = roundButton("🔍", CHIP);
        searchBtn.setOnClickListener(v -> toggleSearch());
        header.addView(searchBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        sortBtn = roundButton("↕ " + SORT_NAMES[0], CHIP);
        sortBtn.setTextSize(13);
        sortBtn.setOnClickListener(v -> {
            sortMode = (sortMode + 1) % SORT_NAMES.length;
            sortBtn.setText("↕ " + SORT_NAMES[sortMode]);
            applyFilter();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        sp.setMarginStart(dp(8));
        header.addView(sortBtn, sp);
        library.addView(header);

        // search
        searchField = new EditText(this);
        searchField.setHint("ابحث باسم الفيديو…");
        searchField.setHintTextColor(MUTED);
        searchField.setTextColor(TEXT);
        searchField.setSingleLine(true);
        searchField.setBackground(rounded(CARD, 14));
        searchField.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchField.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { query = s.toString(); applyFilter(); }
        });
        LinearLayout.LayoutParams sfp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfp.setMargins(dp(14), dp(4), dp(14), dp(6));
        library.addView(searchField, sfp);

        // folder chips
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(8));
        hs.addView(chipRow);
        library.addView(hs);

        // list + state
        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setPadding(dp(8), 0, dp(8), dp(16));
        list.setClipToPadding(false);
        adapter = new VideoAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> openPlayer(pos));
        body.addView(list, match());

        stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(dp(32), 0, dp(32), dp(60));
        stateText = text("", 16, MUTED);
        stateText.setGravity(Gravity.CENTER);
        stateText.setLineSpacing(dp(4), 1f);
        stateBox.addView(stateText);
        stateButton = roundButton("", ACCENT);
        stateButton.setOnClickListener(v -> onStateButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
        bp.topMargin = dp(18);
        stateBox.addView(stateButton, bp);
        body.addView(stateBox, match());
        library.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showState(String message, String button) {
        list.setVisibility(View.GONE);
        stateBox.setVisibility(View.VISIBLE);
        stateText.setText(message);
        if (button == null) {
            stateButton.setVisibility(View.GONE);
        } else {
            stateButton.setVisibility(View.VISIBLE);
            stateButton.setText(button);
        }
    }

    private void toggleSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (searchField.getVisibility() == View.VISIBLE) {
            searchField.setText("");
            searchField.setVisibility(View.GONE);
            imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        } else {
            searchField.setVisibility(View.VISIBLE);
            searchField.requestFocus();
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void buildChips() {
        chipRow.removeAllViews();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Video v : all) {
            Integer n = counts.get(v.folder);
            counts.put(v.folder, n == null ? 1 : n + 1);
        }
        List<Map.Entry<String, Integer>> folders = new ArrayList<>(counts.entrySet());
        Collections.sort(folders, (a, b) -> b.getValue() - a.getValue());
        if (folderFilter != null && !counts.containsKey(folderFilter)) folderFilter = null;
        addChip("الكل (" + all.size() + ")", null);
        for (Map.Entry<String, Integer> e : folders) addChip(e.getKey() + " (" + e.getValue() + ")", e.getKey());
    }

    private void addChip(String label, final String folder) {
        boolean on = (folder == null) ? folderFilter == null : folder.equals(folderFilter);
        TextView chip = text(label, 14, on ? Color.WHITE : TEXT);
        chip.setBackground(rounded(on ? ACCENT : CHIP, 20));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setOnClickListener(v -> { folderFilter = folder; buildChips(); applyFilter(); list.setSelection(0); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chipRow.addView(chip, lp);
    }

    /** List rows: thumbnail with duration, name, folder • size • date. */
    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row row;
            if (convert == null) {
                row = new Row();
                convert = row.build();
                convert.setTag(row);
            } else {
                row = (Row) convert.getTag();
            }
            final Video v = shown.get(pos);
            row.name.setText(v.name);
            row.meta.setText(v.folder + " • " + sizeText(v.size) + " • "
                    + DateUtils.getRelativeTimeSpanString(v.dateAdded * 1000L));
            row.duration.setText(timeText(v.durationMs));
            long resume = prefs.getLong("p" + v.id, 0);
            row.resume.setVisibility(resume > 0 ? View.VISIBLE : View.GONE);
            row.thumb.setTag(v.id);
            Bitmap cached = thumbs.get(v.id);
            if (cached != null) {
                row.thumb.setImageBitmap(cached);
            } else {
                row.thumb.setImageBitmap(null);
                final ImageView target = row.thumb;
                io.execute(() -> {
                    final Bitmap bmp = loadThumb(v);
                    if (bmp == null) return;
                    thumbs.put(v.id, bmp);
                    ui.post(() -> { if (Long.valueOf(v.id).equals(target.getTag())) target.setImageBitmap(bmp); });
                });
            }
            return convert;
        }
    }

    private final class Row {
        ImageView thumb;
        TextView name, meta, duration, resume;

        View build() {
            LinearLayout r = new LinearLayout(VideoPlayer.this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(8), dp(8), dp(8), dp(8));
            r.setBackground(rounded(CARD, 16));

            FrameLayout box = new FrameLayout(VideoPlayer.this);
            thumb = new ImageView(VideoPlayer.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(Color.BLACK, 10));
            thumb.setClipToOutline(true);
            box.addView(thumb, match());
            duration = text("", 11, Color.WHITE);
            duration.setBackground(rounded(Color.parseColor("#B0000000"), 6));
            duration.setPadding(dp(5), dp(1), dp(5), dp(1));
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(dp(4), dp(4), dp(4), dp(4));
            box.addView(duration, dlp);
            r.addView(box, new LinearLayout.LayoutParams(dp(128), dp(72)));

            LinearLayout texts = new LinearLayout(VideoPlayer.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, dp(4), 0);
            name = text("", 15, TEXT);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta = text("", 12, MUTED);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            resume = text("▶ تكمّل من وين وقفت", 11, ACCENT);
            texts.addView(name);
            texts.addView(meta);
            texts.addView(resume);
            r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            FrameLayout wrap = new FrameLayout(VideoPlayer.this);
            wrap.setPadding(0, dp(4), 0, dp(4));
            wrap.addView(r);
            return wrap;
        }
    }

    @SuppressWarnings("deprecation")
    private Bitmap loadThumb(Video v) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(v.uri, new Size(320, 180), null);
            }
            return MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), v.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================= player UI

    private void buildPlayer() {
        player = new FrameLayout(this);
        player.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        player.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        View touch = new View(this);
        touch.setOnClickListener(v -> toggleOverlay());
        player.addView(touch, match());

        centerHint = text("", 18, Color.WHITE);
        centerHint.setBackground(rounded(Color.parseColor("#99000000"), 14));
        centerHint.setPadding(dp(16), dp(8), dp(16), dp(8));
        centerHint.setVisibility(View.GONE);
        player.addView(centerHint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // top overlay
        topOverlay = new LinearLayout(this);
        topOverlay.setOrientation(LinearLayout.HORIZONTAL);
        topOverlay.setGravity(Gravity.CENTER_VERTICAL);
        topOverlay.setPadding(dp(8), dp(10), dp(12), dp(10));
        topOverlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#CC000000"), Color.TRANSPARENT}));
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        Button back = roundButton(rtl ? "→" : "←", Color.TRANSPARENT);
        back.setTextSize(22);
        back.setOnClickListener(v -> closePlayer());
        topOverlay.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playerTitle = text("", 16, Color.WHITE);
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topOverlay.addView(playerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        player.addView(topOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom overlay
        bottomOverlay = new LinearLayout(this);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setPadding(dp(12), dp(24), dp(12), dp(16));
        bottomOverlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#DD000000"), Color.TRANSPARENT}));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeText.setText(timeText(p) + " / " + timeText(s.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; ui.removeCallbacks(hideOverlay); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (prepared) video.seekTo(s.getProgress());
                scheduleHide();
            }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        timeText = text("0:00 / 0:00", 12, Color.WHITE);
        seekRow.addView(timeText);
        bottomOverlay.addView(seekRow);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(6), 0, 0);
        Button prev = roundButton("⏮", Color.TRANSPARENT);
        prev.setOnClickListener(v -> playAt(current - 1));
        Button back10 = roundButton("⏪", Color.TRANSPARENT);
        back10.setOnClickListener(v -> skip(-10000));
        playBtn = roundButton("⏸", ACCENT);
        playBtn.setTextSize(22);
        playBtn.setOnClickListener(v -> togglePlay());
        Button fwd10 = roundButton("⏩", Color.TRANSPARENT);
        fwd10.setOnClickListener(v -> skip(10000));
        Button next = roundButton("⏭", Color.TRANSPARENT);
        next.setOnClickListener(v -> playAt(current + 1));
        speedBtn = roundButton("1x", Color.TRANSPARENT);
        speedBtn.setTextSize(14);
        speedBtn.setOnClickListener(v -> nextSpeed());
        Button full = roundButton("⛶", Color.TRANSPARENT);
        full.setOnClickListener(v -> toggleFullscreen());
        Button[] row = {speedBtn, prev, back10, playBtn, fwd10, next, full};
        for (Button bt : row) {
            bt.setTextColor(Color.WHITE);
            int size = bt == playBtn ? dp(60) : dp(46);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            p.setMargins(dp(3), 0, dp(3), 0);
            buttons.addView(bt, p);
        }
        bottomOverlay.addView(buttons);
        player.addView(bottomOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        video.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            prepared = true;
            seek.setMax(mp.getDuration());
            applySpeed();
            Video v = current >= 0 && current < shown.size() ? shown.get(current) : null;
            long resume = v == null ? 0 : prefs.getLong("p" + v.id, 0);
            if (resume > 3000 && resume < mp.getDuration() - 3000) {
                video.seekTo((int) resume);
                flash("كمّلت من " + timeText(resume));
            }
            video.start();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updatePlayButton();
            ui.post(ticker);
            scheduleHide();
        });
        video.setOnCompletionListener(mp -> {
            if (current >= 0 && current < shown.size()) prefs.edit().remove("p" + shown.get(current).id).apply();
            if (current + 1 < shown.size()) {
                flash("الفيديو اللي بعدو ⏭");
                playAt(current + 1);
            } else {
                updatePlayButton();
                showOverlay();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            flash("ما نجمتش نشغّل الفيديو هذا 😕");
            showOverlay();
            return true;
        });
    }

    private void openPlayer(int pos) {
        library.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(Color.BLACK);
        playAt(pos);
    }

    private void playAt(int pos) {
        if (pos < 0 || pos >= shown.size()) {
            flash(pos < 0 ? "هذا أوّل فيديو" : "هذا آخر فيديو");
            return;
        }
        if (prepared) savePosition();
        current = pos;
        prepared = false;
        Video v = shown.get(pos);
        playerTitle.setText(v.name);
        seek.setProgress(0);
        timeText.setText("0:00 / " + timeText(v.durationMs));
        video.setVideoURI(v.uri);
        video.requestFocus();
        showOverlay();
    }

    private void closePlayer() {
        if (prepared) savePosition();
        video.stopPlayback();
        prepared = false;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideOverlay);
        if (fullscreen) toggleFullscreen();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        player.setVisibility(View.GONE);
        library.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        if (current >= 0) list.setSelection(Math.max(0, current - 1));
    }

    private void savePosition() {
        if (current < 0 || current >= shown.size()) return;
        int pos = video.getCurrentPosition();
        int dur = video.getDuration();
        String key = "p" + shown.get(current).id;
        if (pos > 3000 && dur > 0 && pos < dur - 3000) prefs.edit().putLong(key, pos).apply();
        else prefs.edit().remove(key).apply();
    }

    private void togglePlay() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showOverlay();
        } else {
            video.start();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            scheduleHide();
        }
        updatePlayButton();
    }

    private void skip(int ms) {
        if (!prepared) return;
        int to = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + ms));
        video.seekTo(to);
        flash(ms > 0 ? "+10 ثواني" : "-10 ثواني");
        scheduleHide();
    }

    private void nextSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        applySpeed();
        flash("السرعة " + speedBtn.getText());
        scheduleHide();
    }

    private void applySpeed() {
        float s = SPEEDS[speedIndex];
        speedBtn.setText(s == (int) s ? ((int) s) + "x" : s + "x");
        if (mediaPlayer == null || !prepared) return;
        try {
            boolean playing = video.isPlaying();
            PlaybackParams pp = mediaPlayer.getPlaybackParams().setSpeed(s);
            mediaPlayer.setPlaybackParams(pp);
            if (!playing) mediaPlayer.pause();
        } catch (Exception e) {
            flash("السرعة ما تخدمش على هالفيديو");
        }
    }

    private void updatePlayButton() {
        playBtn.setText(video.isPlaying() ? "⏸" : "▶");
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (prepared && !userSeeking) {
                int pos = video.getCurrentPosition();
                int dur = video.getDuration();
                if (dur > 0) seek.setMax(dur);
                seek.setProgress(pos);
                timeText.setText(timeText(pos) + " / " + timeText(dur));
            }
            if (player.getVisibility() == View.VISIBLE) ui.postDelayed(this, 400);
        }
    };

    // ---------------- overlay

    private final Runnable hideOverlay = () -> {
        if (prepared && video.isPlaying() && !userSeeking) setOverlay(false);
    };

    private void toggleOverlay() {
        if (overlayShown) setOverlay(false); else showOverlay();
    }

    private void showOverlay() {
        setOverlay(true);
        scheduleHide();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, 3500);
    }

    private void setOverlay(boolean show) {
        overlayShown = show;
        topOverlay.animate().alpha(show ? 1f : 0f).setDuration(200).start();
        bottomOverlay.animate().alpha(show ? 1f : 0f).setDuration(200).start();
        topOverlay.setVisibility(View.VISIBLE);
        bottomOverlay.setVisibility(View.VISIBLE);
        if (!show) {
            ui.postDelayed(() -> {
                if (!overlayShown) { topOverlay.setVisibility(View.GONE); bottomOverlay.setVisibility(View.GONE); }
            }, 220);
        }
    }

    private void flash(String msg) {
        centerHint.setText(msg);
        centerHint.setVisibility(View.VISIBLE);
        centerHint.setAlpha(1f);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 1300);
    }

    private final Runnable hideHint = () -> centerHint.setVisibility(View.GONE);

    // ---------------- full screen

    @SuppressWarnings("deprecation")
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        View decor = getWindow().getDecorView();
        if (fullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        scheduleHide();
    }

    // ================================================================= helpers

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button roundButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(18);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(rounded(color, 23));
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

    private static String timeText(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.0f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
