package de.omatv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Oma TV: Fernsehen wie frueher.
 * Hoch/Runter schaltet um, OK zeigt die Senderliste, Zahlen waehlen einen Sender direkt.
 * Eingerichtet wird alles vom Handy aus ueber die Adresse, die die App anzeigt.
 */
public class MainActivity extends Activity implements SetupServer.Backend {

    static final String PREFS = "omatv";
    static final String KEY_SOURCES = "sources";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_LAST = "last";

    private static final String VERSION = "1.0";
    private static final String USER_AGENT = "OmaTV/" + VERSION + " (Android TV)";
    private static final long REFRESH_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final long RETRY_REFRESH_MS = 2L * 60 * 1000;
    private static final long ZAP_DELAY_MS = 350;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int AMBER = 0xFFFFC107;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFB8C0C8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private File cacheFile;
    private String pageHtml = "";

    // Zustand, den auch die Einrichtungsseite liest (deshalb volatile und unveraenderliche Listen)
    private volatile List<Channel> all = Collections.emptyList();
    private volatile List<Channel> visible = Collections.emptyList();
    private volatile List<String> favKeys = Collections.emptyList();
    private volatile String sources = "";
    private volatile String status = "";
    private volatile boolean loading;
    private volatile String currentKey = "";
    private volatile int revision;

    // Nur im Hauptthread benutzt
    private Channel current;
    private int index = -1;
    private int previousIndex = -1;
    private int pendingLoads;
    private long lastRefresh;
    private boolean cacheLoaded;
    private boolean started;
    private boolean destroyed;

    private ExoPlayer player;
    private int failures;
    private String playingUrl = "";
    private boolean playingHls;
    private final Map<String, Boolean> hlsByUrl = new HashMap<String, Boolean>();
    private VideoSize videoSize;

    private SetupServer server;
    private String address = "";

    private FrameLayout root;
    private SurfaceView surface;
    private LinearLayout banner;
    private TextView bannerNumber;
    private TextView bannerName;
    private TextView bannerClock;
    private TextView loadingView;
    private TextView numberView;
    private LinearLayout setupBox;
    private TextView setupAddress;
    private TextView setupStatus;
    private LinearLayout listBox;
    private TextView[] rows;
    private TextView listFooter;
    private boolean listOpen;
    private int listSel;
    private final StringBuilder digits = new StringBuilder();

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sources = prefs.getString(KEY_SOURCES, "");
        favKeys = Collections.unmodifiableList(splitLines(prefs.getString(KEY_FAVORITES, "")));
        currentKey = prefs.getString(KEY_LAST, "");
        cacheFile = new File(getFilesDir(), "sender.tsv");
        pageHtml = readAsset("setup.html");
        buildUi();
        updateMode();

        io.execute(new Runnable() {
            @Override
            public void run() {
                List<Channel> cached;
                try {
                    cached = PlaylistLoader.readCache(cacheFile);
                } catch (Exception e) {
                    cached = new ArrayList<Channel>();
                }
                final List<Channel> result = cached;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        cacheLoaded = true;
                        if (!result.isEmpty() && all.isEmpty()) {
                            status = result.size() + " Sender (gespeicherte Liste)";
                            setAll(result);
                        }
                        updateMode();
                        maybeRefresh();
                    }
                });
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        startServer();
        createPlayer();
        updateMode();
        if (current != null) {
            failures = 0;
            showBanner();
            startPlayback();
        }
        if (cacheLoaded) maybeRefresh();
        ui.postDelayed(periodic, 30L * 60 * 1000);
        ui.postDelayed(addressCheck, 8000);
    }

    @Override
    protected void onStop() {
        started = false;
        ui.removeCallbacks(playRunnable);
        ui.removeCallbacks(retryRunnable);
        ui.removeCallbacks(stallRunnable);
        ui.removeCallbacks(showLoading);
        ui.removeCallbacks(hideBanner);
        ui.removeCallbacks(closeListRunnable);
        ui.removeCallbacks(commitDigits);
        ui.removeCallbacks(periodic);
        ui.removeCallbacks(addressCheck);
        closeList();
        digits.setLength(0);
        numberView.setVisibility(View.GONE);
        loadingView.setVisibility(View.GONE);
        releasePlayer();
        stopServer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Oberflaeche

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setFocusable(false);
        return t;
    }

    private TextView oneLine(float sp, int color, boolean bold) {
        TextView t = text(sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);

        surface = new SurfaceView(this);
        surface.setFocusable(false);
        root.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        // Hinweis "Sender wird geladen"
        loadingView = text(28, WHITE, false);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setBackgroundColor(0xB0000000);
        loadingView.setPadding(dp(28), dp(16), dp(28), dp(16));
        loadingView.setVisibility(View.GONE);
        root.addView(loadingView, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        // Senderanzeige unten
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundColor(0xD0101820);
        banner.setPadding(dp(48), dp(14), dp(48), dp(30));
        bannerNumber = oneLine(46, AMBER, true);
        bannerName = oneLine(38, WHITE, true);
        bannerClock = oneLine(30, GREY, false);
        banner.addView(bannerNumber, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        nameLp.leftMargin = dp(24);
        nameLp.rightMargin = dp(24);
        banner.addView(bannerName, nameLp);
        banner.addView(bannerClock, new LinearLayout.LayoutParams(WRAP, WRAP));
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        // Zifferneingabe oben rechts
        numberView = text(64, AMBER, true);
        numberView.setBackgroundColor(0xD0101820);
        numberView.setPadding(dp(28), dp(6), dp(28), dp(6));
        numberView.setVisibility(View.GONE);
        FrameLayout.LayoutParams numLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT);
        numLp.topMargin = dp(27);
        numLp.rightMargin = dp(48);
        root.addView(numberView, numLp);

        // Senderliste links
        float heightDp = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density;
        int rowCount = Math.max(5, Math.min(9, (int) ((heightDp - 160) / 52)));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackgroundColor(0xEE101820);
        listBox.setPadding(dp(32), dp(22), dp(20), dp(18));
        TextView header = oneLine(30, AMBER, true);
        header.setText("Sender");
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        headLp.bottomMargin = dp(8);
        listBox.addView(header, headLp);
        rows = new TextView[rowCount];
        for (int i = 0; i < rowCount; i++) {
            TextView row = oneLine(26, WHITE, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(14), 0);
            rows[i] = row;
            listBox.addView(row, new LinearLayout.LayoutParams(MATCH, dp(52)));
        }
        listFooter = oneLine(15, GREY, false);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        footLp.topMargin = dp(10);
        listBox.addView(listFooter, footLp);
        listBox.setVisibility(View.GONE);
        root.addView(listBox, new FrameLayout.LayoutParams(dp(470), MATCH, Gravity.LEFT));

        // Einrichtungsbild (solange es noch keine Sender gibt)
        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setGravity(Gravity.CENTER_VERTICAL);
        setupBox.setBackgroundColor(0xFF0D1B2A);
        setupBox.setPadding(dp(96), dp(40), dp(96), dp(40));
        TextView title = text(42, WHITE, true);
        title.setText("Oma TV einrichten");
        setupBox.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView steps = text(26, WHITE, false);
        steps.setText("1.  Handy ins gleiche WLAN wie diesen Fernseher bringen\n"
                + "2.  Im Browser des Handys diese Adresse öffnen:");
        steps.setLineSpacing(dp(6), 1f);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        stepsLp.topMargin = dp(26);
        setupBox.addView(steps, stepsLp);
        setupAddress = text(46, AMBER, true);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        addrLp.topMargin = dp(14);
        addrLp.bottomMargin = dp(14);
        addrLp.leftMargin = dp(48);
        setupBox.addView(setupAddress, addrLp);
        TextView step3 = text(26, WHITE, false);
        step3.setText("3.  Dort den M3U-Link einfügen und Omas Sender auswählen");
        setupBox.addView(step3, new LinearLayout.LayoutParams(MATCH, WRAP));
        setupStatus = text(22, GREY, false);
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        statLp.topMargin = dp(34);
        setupBox.addView(setupStatus, statLp);
        setupBox.setVisibility(View.GONE);
        root.addView(setupBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        setContentView(root);
    }

    /** Zeigt je nach Zustand das Einrichtungsbild oder das Fernsehbild. */
    private void updateMode() {
        boolean empty = visible.isEmpty();
        setupBox.setVisibility(empty && cacheLoaded ? View.VISIBLE : View.GONE);
        if (empty) {
            closeList();
            banner.setVisibility(View.GONE);
            loadingView.setVisibility(View.GONE);
        }
        updateTexts();
    }

    private void updateTexts() {
        setupAddress.setText(address.isEmpty() ? "Kein WLAN gefunden – bitte Netzwerk prüfen" : address);
        setupStatus.setText(status);
        listFooter.setText(address.isEmpty() ? "" : "Einstellungen am Handy:  " + address);
    }

    private void showBanner() {
        if (current == null || visible.isEmpty()) return;
        bannerNumber.setText(index >= 0 ? String.valueOf(index + 1) : "Vorschau");
        bannerName.setText(current.name);
        bannerClock.setText(new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()) + " Uhr");
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 4500);
    }

    private final Runnable hideBanner = new Runnable() {
        @Override
        public void run() {
            banner.setVisibility(View.GONE);
        }
    };

    private final Runnable showLoading = new Runnable() {
        @Override
        public void run() {
            if (!started || current == null || visible.isEmpty()) return;
            loadingView.setText(failures >= 3
                    ? "Dieser Sender sendet gerade nicht.\nEs wird weiter versucht …\n\n▲ ▼  anderen Sender wählen"
                    : "Sender wird geladen …");
            loadingView.setVisibility(View.VISIBLE);
        }
    };

    /** Passt das Bild ohne Verzerrung in den Bildschirm ein. */
    private void fitVideo() {
        VideoSize size = videoSize;
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        if (size == null || size.width <= 0 || size.height <= 0 || screenW <= 0 || screenH <= 0) return;
        float ratio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
        float aspect = size.width * ratio / size.height;
        int w = screenW;
        int h = Math.round(screenW / aspect);
        if (h > screenH) {
            h = screenH;
            w = Math.round(screenH * aspect);
        }
        // Fast bildschirmfuellend: kleine Raender vermeiden
        if (screenW - w < screenW * 0.03f && screenH - h < screenH * 0.03f) {
            w = MATCH;
            h = MATCH;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------ Fernbedienung

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (visible.isEmpty() || !isOurKey(code)) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) onKey(code, event.getRepeatCount());
        return true;
    }

    private static int digitOf(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private static boolean isOurKey(int code) {
        if (digitOf(code) >= 0) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
                return true;
            default:
                return false;
        }
    }

    private void onKey(int code, int repeat) {
        int digit = digitOf(code);
        if (listOpen) {
            if (digit >= 0) {
                closeList();
                if (repeat == 0) onDigit(digit);
                return;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveList(-1, true);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveList(1, true);
                    break;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    moveList(-rows.length, false);
                    break;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    moveList(rows.length, false);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (repeat == 0) chooseFromList();
                    break;
                default:
                    if (repeat == 0) closeList();
                    break;
            }
            return;
        }
        if (digit >= 0) {
            if (repeat == 0) onDigit(digit);
            return;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                step(1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                step(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_GUIDE:
                if (repeat == 0) openList();
                break;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (repeat == 0 && previousIndex >= 0 && previousIndex < visible.size()) {
                    tune(visible.get(previousIndex), previousIndex, 0);
                }
                break;
            default:
                // Zurueck/Info: nur die Senderanzeige einblenden. Die App wird nicht versehentlich verlassen.
                if (repeat == 0) showBanner();
                break;
        }
    }

    /** Einen Sender weiter (+1) oder zurueck (-1). */
    private void step(int delta) {
        List<Channel> v = visible;
        int n = v.size();
        if (n == 0) return;
        int target;
        if (index < 0) {
            target = Math.max(0, indexOfKey(v, prefs.getString(KEY_LAST, "")));
        } else {
            target = ((index + delta) % n + n) % n;
        }
        tune(v.get(target), target, ZAP_DELAY_MS);
    }

    private void onDigit(int digit) {
        if (digits.length() >= 4) digits.setLength(0);
        digits.append(digit);
        numberView.setText(digits.toString());
        numberView.setVisibility(View.VISIBLE);
        int n = Integer.parseInt(digits.toString());
        boolean moreDigitsPossible = n > 0 && (long) n * 10 <= visible.size();
        ui.removeCallbacks(commitDigits);
        ui.postDelayed(commitDigits, moreDigitsPossible ? 1800 : 700);
    }

    private final Runnable commitDigits = new Runnable() {
        @Override
        public void run() {
            numberView.setVisibility(View.GONE);
            if (digits.length() == 0) return;
            int n = Integer.parseInt(digits.toString());
            digits.setLength(0);
            List<Channel> v = visible;
            if (n >= 1 && n <= v.size()) tune(v.get(n - 1), n - 1, 0);
            else showBanner();
        }
    };

    // ------------------------------------------------------------------ Senderliste

    private void openList() {
        if (visible.isEmpty()) return;
        listOpen = true;
        listSel = index >= 0 ? index : 0;
        renderList();
        listBox.setVisibility(View.VISIBLE);
        banner.setVisibility(View.GONE);
        keepListOpen();
    }

    private void closeList() {
        listOpen = false;
        if (listBox != null) listBox.setVisibility(View.GONE);
        ui.removeCallbacks(closeListRunnable);
    }

    private final Runnable closeListRunnable = new Runnable() {
        @Override
        public void run() {
            closeList();
        }
    };

    private void keepListOpen() {
        ui.removeCallbacks(closeListRunnable);
        ui.postDelayed(closeListRunnable, 20000);
    }

    private void moveList(int delta, boolean wrap) {
        int n = visible.size();
        if (n == 0) return;
        int target = listSel + delta;
        if (wrap) target = (target % n + n) % n;
        else target = Math.max(0, Math.min(n - 1, target));
        listSel = target;
        renderList();
        keepListOpen();
    }

    private void chooseFromList() {
        List<Channel> v = visible;
        if (listSel >= 0 && listSel < v.size()) tune(v.get(listSel), listSel, 0);
        closeList();
    }

    private void renderList() {
        List<Channel> v = visible;
        int n = v.size();
        if (listSel >= n) listSel = Math.max(0, n - 1);
        int top = listSel - rows.length / 2;
        if (top > n - rows.length) top = n - rows.length;
        if (top < 0) top = 0;
        for (int r = 0; r < rows.length; r++) {
            int i = top + r;
            TextView row = rows[r];
            if (i >= n) {
                row.setVisibility(View.INVISIBLE);
                continue;
            }
            boolean selected = i == listSel;
            row.setText((i + 1) + "    " + v.get(i).name);
            row.setBackgroundColor(selected ? AMBER : Color.TRANSPARENT);
            row.setTextColor(selected ? Color.BLACK : (i == index ? AMBER : WHITE));
            row.setTypeface(selected || i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ Wiedergabe

    private void createPlayer() {
        if (player != null) return;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        ExoPlayer p = new ExoPlayer.Builder(this, renderers).build();
        p.setAudioAttributes(AudioAttributes.DEFAULT, true);
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(Locale.getDefault().getLanguage())
                .build());
        p.setVideoSurfaceView(surface);
        p.addListener(playerListener);
        player = p;
    }

    private void releasePlayer() {
        if (player == null) return;
        player.removeListener(playerListener);
        player.release();
        player = null;
        playingUrl = "";
    }

    /** Schaltet auf einen Sender. idx ist die Position in Omas Liste oder -1 fuer eine Vorschau vom Handy. */
    private void tune(Channel channel, int idx, long delayMs) {
        if (idx >= 0 && index >= 0 && idx != index) previousIndex = index;
        current = channel;
        index = idx;
        currentKey = channel.key;
        failures = 0;
        if (idx >= 0) prefs.edit().putString(KEY_LAST, channel.key).apply();
        loadingView.setVisibility(View.GONE);
        showBanner();
        if (listOpen) renderList();
        ui.removeCallbacks(playRunnable);
        if (delayMs <= 0) startPlayback();
        else ui.postDelayed(playRunnable, delayMs);
    }

    private final Runnable playRunnable = new Runnable() {
        @Override
        public void run() {
            startPlayback();
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            startPlayback();
        }
    };

    private final Runnable stallRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) scheduleRetry();
        }
    };

    private void startPlayback() {
        ui.removeCallbacks(playRunnable);
        ui.removeCallbacks(retryRunnable);
        ui.removeCallbacks(stallRunnable);
        if (!started || player == null || current == null) return;
        Channel ch = current;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(ch.userAgent != null ? ch.userAgent : USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(12000)
                    .setReadTimeoutMs(15000);
            if (ch.referrer != null) {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Referer", ch.referrer);
                http.setDefaultRequestProperties(headers);
            }
            boolean hls = useHls(ch.url, failures);
            MediaItem.Builder item = new MediaItem.Builder().setUri(ch.url);
            if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
            DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
            playingUrl = ch.url;
            playingHls = hls;
            player.setMediaSource(new DefaultMediaSourceFactory(http, extractors).createMediaSource(item.build()));
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (RuntimeException e) {
            scheduleRetry();
            return;
        }
        ui.removeCallbacks(showLoading);
        ui.postDelayed(showLoading, 900);
        ui.postDelayed(stallRunnable, 25000);
    }

    /**
     * Manche Adressen verraten nicht, ob dahinter HLS oder ein direkter Datenstrom steckt.
     * Dann wird abwechselnd beides probiert; was funktioniert hat, wird gemerkt.
     */
    private boolean useHls(String url, int attempt) {
        String path = url.toLowerCase(Locale.ROOT);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true;
        if (path.endsWith(".ts") || path.endsWith(".mpd") || path.endsWith(".mp4")
                || path.endsWith(".mkv") || path.endsWith(".aac") || path.endsWith(".mp3")) return false;
        Boolean known = hlsByUrl.get(url);
        boolean first = known != null && known.booleanValue();
        return attempt % 2 == 0 ? first : !first;
    }

    private void scheduleRetry() {
        failures++;
        ui.removeCallbacks(retryRunnable);
        ui.removeCallbacks(stallRunnable);
        if (!started || current == null) return;
        if (failures >= 2) {
            ui.removeCallbacks(showLoading);
            showLoading.run();
        }
        // Vielleicht haben sich die Adressen in der Senderliste geaendert
        if (failures % 5 == 0 && System.currentTimeMillis() - lastRefresh > RETRY_REFRESH_MS) refresh();
        long delay = failures == 1 ? 300 : Math.min(2000L * (failures - 1), 10000L);
        ui.postDelayed(retryRunnable, delay);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                failures = 0;
                if (!playingUrl.isEmpty()) hlsByUrl.put(playingUrl, Boolean.valueOf(playingHls));
                ui.removeCallbacks(stallRunnable);
                ui.removeCallbacks(showLoading);
                loadingView.setVisibility(View.GONE);
            } else if (state == Player.STATE_BUFFERING) {
                ui.removeCallbacks(showLoading);
                ui.postDelayed(showLoading, 900);
                ui.removeCallbacks(stallRunnable);
                ui.postDelayed(stallRunnable, 25000);
            } else if (state == Player.STATE_ENDED) {
                scheduleRetry();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            scheduleRetry();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            videoSize = size;
            fitVideo();
        }
    };

    // ------------------------------------------------------------------ Senderliste laden

    private void maybeRefresh() {
        if (PlaylistLoader.parseSources(sources).isEmpty()) {
            if (all.isEmpty()) status = "Noch kein M3U-Link hinterlegt";
            updateTexts();
            return;
        }
        if (all.isEmpty() || System.currentTimeMillis() - lastRefresh > REFRESH_EVERY_MS) refresh();
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            maybeRefresh();
            ui.postDelayed(this, 30L * 60 * 1000);
        }
    };

    private void refresh() {
        final String src = sources;
        if (PlaylistLoader.parseSources(src).isEmpty()) {
            status = "Fehler: Das ist kein gültiger Link";
            updateTexts();
            return;
        }
        pendingLoads++;
        loading = true;
        lastRefresh = System.currentTimeMillis();
        status = "Senderliste wird geladen …";
        updateTexts();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PlaylistLoader.Outcome outcome = PlaylistLoader.loadAll(src, USER_AGENT);
                if (!outcome.channels.isEmpty()) {
                    try {
                        PlaylistLoader.writeCache(cacheFile, outcome.channels);
                    } catch (Exception ignored) {
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        pendingLoads--;
                        loading = pendingLoads > 0;
                        if (!destroyed) applyOutcome(src, outcome);
                    }
                });
            }
        });
    }

    private void applyOutcome(String src, PlaylistLoader.Outcome outcome) {
        if (!src.equals(sources)) return; // inzwischen wurde ein anderer Link gespeichert
        if (!outcome.channels.isEmpty()) {
            String time = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date());
            String s = outcome.channels.size() + " Sender geladen (" + time + " Uhr)";
            if (outcome.skippedVod > 0) s += " · " + outcome.skippedVod + " Filme/Serien ausgeblendet";
            if (!outcome.errors.isEmpty()) s += " · Problem bei " + outcome.errors.get(0);
            status = s;
            lastRefresh = System.currentTimeMillis();
            setAll(outcome.channels);
        } else {
            String reason = outcome.errors.isEmpty() ? "keine Sender gefunden" : outcome.errors.get(0);
            status = "Fehler: " + reason + (all.isEmpty() ? "" : " – die bisherige Liste bleibt aktiv");
            // Nicht erst in sechs Stunden wieder versuchen
            lastRefresh = System.currentTimeMillis() - REFRESH_EVERY_MS + RETRY_REFRESH_MS;
            updateTexts();
        }
    }

    private void setAll(List<Channel> channels) {
        all = Collections.unmodifiableList(new ArrayList<Channel>(channels));
        revision++;
        rebuildVisible();
    }

    /** Baut Omas Liste aus der Auswahl (oder aus allen Sendern, wenn nichts ausgewaehlt ist). */
    private void rebuildVisible() {
        List<Channel> source = all;
        List<Channel> v = new ArrayList<Channel>();
        if (!favKeys.isEmpty()) {
            Map<String, Channel> byKey = new HashMap<String, Channel>();
            for (int i = 0; i < source.size(); i++) byKey.put(source.get(i).key, source.get(i));
            List<String> keys = favKeys;
            for (int i = 0; i < keys.size(); i++) {
                Channel c = byKey.get(keys.get(i));
                if (c != null) v.add(c);
            }
        }
        if (v.isEmpty()) v.addAll(source);
        visible = Collections.unmodifiableList(v);
        previousIndex = -1;

        if (v.isEmpty()) {
            index = -1;
            current = null;
            if (player != null) player.stop();
        } else {
            boolean preview = current != null && index < 0;
            int found = indexOfKey(v, currentKey);
            if (found >= 0) {
                Channel c = v.get(found);
                boolean changed = current == null || !current.url.equals(c.url);
                current = c;
                index = found;
                if (changed && started) {
                    failures = 0;
                    showBanner();
                    startPlayback();
                }
            } else if (preview && indexOfKey(source, currentKey) >= 0) {
                // Die Vorschau vom Handy laeuft einfach weiter.
                index = -1;
            } else {
                int i = index < 0 ? 0 : Math.min(index, v.size() - 1);
                if (started) {
                    tune(v.get(i), i, 0);
                } else {
                    current = v.get(i);
                    index = i;
                    currentKey = current.key;
                }
            }
        }
        updateMode();
        if (listOpen) renderList();
    }

    private static int indexOfKey(List<Channel> list, String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (key.equals(list.get(i).key)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ Einrichtung vom Handy

    private void startServer() {
        final SetupServer s = new SetupServer(this, pageHtml);
        server = s;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String found = "";
                try {
                    int port = s.start();
                    String ip = findLocalIp();
                    if (ip != null) found = "http://" + ip + ":" + port;
                } catch (Exception ignored) {
                }
                final String result = found;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (server != s || destroyed) return;
                        address = result;
                        updateTexts();
                    }
                });
            }
        }, "oma-tv-start").start();
    }

    private void stopServer() {
        final SetupServer s = server;
        server = null;
        if (s == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                s.stop();
            }
        }, "oma-tv-stop").start();
    }

    /** Prueft regelmaessig, ob sich die Adresse im WLAN geaendert hat (z. B. WLAN war beim Start noch nicht da). */
    private final Runnable addressCheck = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            final SetupServer s = server;
            if (s != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String found = "";
                        try {
                            int port = s.start();
                            String ip = findLocalIp();
                            if (ip != null) found = "http://" + ip + ":" + port;
                        } catch (Exception ignored) {
                        }
                        final String result = found;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (server != s || destroyed || result.equals(address)) return;
                                address = result;
                                updateTexts();
                            }
                        });
                    }
                }, "oma-tv-address").start();
            }
            ui.postDelayed(this, 15000);
        }
    };

    private static String findLocalIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = nif.getName() == null ? "" : nif.getName();
                    if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("eth"))) {
                        return a.getHostAddress();
                    }
                    if (fallback == null || a.isSiteLocalAddress()) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private String readAsset(String name) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8));
            try {
                StringBuilder sb = new StringBuilder(32768);
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return "<html><body>Einrichtungsseite fehlt</body></html>";
        }
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] parts = text.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    // ---- SetupServer.Backend: wird aus Server-Threads aufgerufen ----

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getSources() {
        return sources;
    }

    @Override
    public void setSources(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                sources = text;
                prefs.edit().putString(KEY_SOURCES, text).apply();
                refresh();
            }
        });
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public boolean isLoading() {
        return loading;
    }

    @Override
    public int getRevision() {
        return revision;
    }

    @Override
    public List<Channel> getAllChannels() {
        return all;
    }

    @Override
    public List<String> getFavoriteKeys() {
        return favKeys;
    }

    @Override
    public void setFavoriteKeys(final List<String> keys) {
        final List<String> copy = Collections.unmodifiableList(new ArrayList<String>(keys));
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                favKeys = copy;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < copy.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(copy.get(i));
                }
                prefs.edit().putString(KEY_FAVORITES, sb.toString()).apply();
                rebuildVisible();
            }
        });
    }

    @Override
    public String getCurrentKey() {
        return currentKey;
    }

    @Override
    public void reload() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) refresh();
            }
        });
    }

    @Override
    public void play(final String key) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || !started) return;
                closeList();
                int i = indexOfKey(visible, key);
                if (i >= 0) {
                    tune(visible.get(i), i, 0);
                    return;
                }
                List<Channel> source = all;
                int j = indexOfKey(source, key);
                if (j >= 0) tune(source.get(j), -1, 0);
            }
        });
    }

    @Override
    public boolean isAutostartAllowed() {
        return Settings.canDrawOverlays(this);
    }

    @Override
    public void requestAutostart() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception e2) {
                        try {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        });
    }
}
