package com.bhaskar.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSocket;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity implements TvClient.Listener {

    static final int BG = 0xFF0F1117, SURFACE = 0xFF1A1D29, SURFACE2 = 0xFF242838, ACCENT = 0xFF6D5BFF,
            TEXT = 0xFFF2F3F7, MUTED = 0xFF8B90A5, DANGER = 0xFFEF4444, GOOD = 0xFF22C55E, WARN = 0xFFF59E0B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, String> found = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private TvClient client;
    private Discovery discovery;
    private Pairing pairing;
    private AlertDialog codeDialog, progressDialog;
    private boolean pairingActive, resumed;
    private int retries;
    private long lastToast;
    private String host = "", tvName = "";

    private FrameLayout content;
    private View[] pages = new View[4];
    private ImageView[] navIcons = new ImageView[3];
    private TextView[] navLabels = new TextView[3];
    private TextView chipText;
    private View chipDot;
    private LinearLayout deviceList;
    private TextView scanStatus;
    private EditText kbInput;
    private String kbLast = "";
    private boolean kbSilent;
    private String failKind = "";
    private boolean autoSearching;
    private long lastAuto;
    private TextView diagText;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        host = prefs.getString("host", "");
        tvName = prefs.getString("name", "");
        client = new TvClient(this, this);
        discovery = new Discovery();
        buildUi();
        updateChip();
        if (!host.isEmpty()) {
            show(0);
            client.connect(host);
        } else {
            show(3);
            startDiscovery();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!host.isEmpty() && client.state() != TvClient.READY && client.state() != TvClient.CONNECTING && !pairingActive) {
            retries = 0;
            client.connect(host);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.disconnect();
        discovery.stop();
        if (pairing != null) pairing.cancel();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && client.ready()) {
            client.key(k == KeyEvent.KEYCODE_VOLUME_UP ? Keys.VOL_UP : Keys.VOL_DOWN);
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && client.ready()) return true;
        return super.onKeyUp(k, e);
    }

    @Override
    public void onBackPressed() {
        if (pages[3].getVisibility() == View.VISIBLE && !host.isEmpty()) { show(0); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ TvClient.Listener
    @Override
    public void onState(final int s, final String msg) {
        runOnUiThread(() -> {
            updateChip();
            if (s == TvClient.READY) {
                retries = 0;
                failKind = "";
            } else if (s == TvClient.FAILED) {
                failKind = msg == null ? "" : msg;
                updateChip();
                if ("pair".equals(msg)) {
                    if (!pairingActive && !host.isEmpty()) beginPairing(host);
                } else if (resumed && retries++ < 4 && !host.isEmpty()) {
                    autoRediscover();
                    final String h = host;
                    ui.postDelayed(() -> {
                        if (resumed && h.equals(host) && client.state() == TvClient.FAILED && !pairingActive) client.connect(h);
                    }, 3000);
                }
            }
        });
    }

    @Override
    public void onVolume(int level, int max, boolean muted) { }

    // ------------------------------------------------------------------ pairing
    private String tips() {
        return "Tips: close the Google TV app and any other remote app on every phone, keep the TV on its home screen, then try again.";
    }

    private void showProgress(String msg) {
        if (progressDialog == null || !progressDialog.isShowing()) {
            progressDialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Pairing")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setNegativeButton("Cancel", (d, w) -> {
                        if (pairing != null) pairing.cancel();
                        pairingActive = false;
                        updateChip();
                    })
                    .create();
            progressDialog.show();
        } else {
            progressDialog.setMessage(msg);
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try { progressDialog.dismiss(); } catch (Exception ignored) { }
            progressDialog = null;
        }
    }

    private void beginPairing(final String h) {
        if (pairing != null) pairing.cancel();
        client.disconnect();
        Trace.clear();
        pairingActive = true;
        updateChip();
        showProgress("Connecting to your TV...");
        pairing = new Pairing(this, h, new Pairing.Cb() {
            @Override
            public void status(final String text) {
                runOnUiThread(() -> { if (pairingActive) showProgress(text); });
            }

            @Override
            public void needCode(final String error) {
                runOnUiThread(() -> {
                    dismissProgress();
                    showCodeDialog(error);
                });
            }

            @Override
            public void done(final boolean ok, final String message) {
                runOnUiThread(() -> {
                    pairingActive = false;
                    dismissProgress();
                    if (codeDialog != null) { try { codeDialog.dismiss(); } catch (Exception ignored) { } }
                    if (ok) {
                        toast("Paired with your TV");
                        ui.postDelayed(() -> client.connect(h), 1200);
                    } else if (message.startsWith("Pairing cancelled")) {
                        toast(message);
                    } else if (message.contains("ConnectException") || message.contains("SocketTimeoutException")) {
                        final String m2 = message;
                        new Thread(() -> {
                            final String rep = Diag.report(MainActivity.this, h, false);
                            runOnUiThread(() -> showInfo("Could not reach the TV's pairing service",
                                    m2 + "\n\n" + rep + "\n" + tips() + "\n\nLog:\n" + Trace.tail(8)));
                        }).start();
                        autoRediscover();
                    } else {
                        showInfo("Pairing did not finish", message + "\n\n" + tips() + "\n\nLog:\n" + Trace.tail(12));
                    }
                    updateChip();
                });
            }
        });
        pairing.start();
    }

    private void showCodeDialog(String error) {
        if (codeDialog != null) { try { codeDialog.dismiss(); } catch (Exception ignored) { } }
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        e.setTextSize(28);
        e.setGravity(Gravity.CENTER);
        e.setLetterSpacing(0.25f);
        e.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        e.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(e);
        String msg = "A code is now showing on your TV screen. Type it here to pair this phone.";
        if (error != null) msg += "\n\n" + error;
        codeDialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Pair with your TV")
                .setMessage(msg)
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Pair", (d, w) -> { if (pairing != null) pairing.submit(e.getText().toString()); })
                .setNegativeButton("Cancel", (d, w) -> {
                    if (pairing != null) pairing.cancel();
                    pairingActive = false;
                    updateChip();
                })
                .create();
        codeDialog.show();
        e.requestFocus();
    }

    // ------------------------------------------------------------------ TV selection
    private void selectTv(String h, String name) {
        host = h;
        tvName = name;
        prefs.edit().putString("host", h).putString("name", name).apply();
        discovery.stop();
        retries = 0;
        show(0);
        updateChip();
        client.connect(h);
    }

    private void startDiscovery() {
        found.clear();
        refreshDevices();
        scanStatus.setText("Searching for TVs on your Wi-Fi...");
        discovery.stop();
        Discovery.Cb cb = new Discovery.Cb() {
            @Override
            public void found(final String name, final String h) {
                runOnUiThread(() -> addDevice(name, h));
            }

            @Override
            public void finished() {
                runOnUiThread(() -> {
                    if (found.isEmpty()) {
                        scanStatus.setText("No TV found. Check that the TV is on and on the same Wi-Fi, or enter its IP address.");
                    } else {
                        scanStatus.setText("Tap your TV to connect.");
                    }
                });
            }
        };
        discovery.start(this, cb);
    }

    private void addDevice(String name, String h) {
        String old = found.get(h);
        if (old == null || old.equals("Android TV")) found.put(h, name);
        scanStatus.setText("Tap your TV to connect.");
        refreshDevices();
    }

    private void refreshDevices() {
        deviceList.removeAllViews();
        if (!host.isEmpty() && !found.containsKey(host)) {
            deviceList.addView(deviceRow(tvName.isEmpty() ? "Android TV" : tvName, host, true));
        }
        for (Map.Entry<String, String> en : found.entrySet()) {
            deviceList.addView(deviceRow(en.getValue(), en.getKey(), en.getKey().equals(host)));
        }
    }

    private View deviceRow(final String name, final String ip, boolean current) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.setBackground(ripple(rect(SURFACE, 16)));
        ImageView ic = icon(R.drawable.ic_tv, current ? ACCENT : TEXT, 26);
        r.addView(ic);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(name, 16, TEXT, true));
        t.addView(text(ip + (current ? "   (selected)" : ""), 12, MUTED, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(14);
        r.addView(t, tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> selectTv(ip, name));
        return r;
    }

    private void askIp() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setHint("192.168.1.20");
        e.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(e);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("TV IP address")
                .setMessage("On the TV: Settings > Network shows its IP address.")
                .setView(wrap)
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = e.getText().toString().trim();
                    if (!ip.isEmpty()) selectTv(ip, "Android TV");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ find the TV again
    /** The router may give the TV a new address; look for it again and switch to it. */
    private void autoRediscover() {
        long now = SystemClock.uptimeMillis();
        if (autoSearching || now - lastAuto < 15000) return;
        autoSearching = true;
        lastAuto = now;
        final java.util.ArrayList<String[]> cands = new java.util.ArrayList<>();
        final Discovery d2 = new Discovery();
        Discovery.Cb cb = new Discovery.Cb() {
            @Override
            public void found(final String name, final String h) {
                runOnUiThread(() -> { if (!h.equals(host)) cands.add(new String[]{name, h}); });
            }

            @Override
            public void finished() {
                ui.postDelayed(() -> {
                    d2.stop();
                    autoSearching = false;
                    pickCandidate(cands);
                }, 2000);
            }
        };
        d2.start(this, cb);
    }

    private void pickCandidate(java.util.ArrayList<String[]> cands) {
        if (client.ready() || pairingActive) return;
        LinkedHashMap<String, String> uniq = new LinkedHashMap<>();
        for (String[] c : cands) {
            String old = uniq.get(c[1]);
            if (old == null || old.equals("Android TV")) uniq.put(c[1], c[0]);
        }
        String[] best = null;
        for (Map.Entry<String, String> en : uniq.entrySet()) {
            if (!tvName.isEmpty() && en.getValue().equalsIgnoreCase(tvName)) best = new String[]{en.getValue(), en.getKey()};
        }
        if (best == null && uniq.size() == 1) {
            Map.Entry<String, String> en = uniq.entrySet().iterator().next();
            best = new String[]{en.getValue(), en.getKey()};
        }
        if (best == null) return;
        String nm = best[0].equals("Android TV") && !tvName.isEmpty() ? tvName : best[0];
        Toast.makeText(this, "Found your TV at " + best[1], Toast.LENGTH_LONG).show();
        selectTv(best[1], nm);
    }

    // ------------------------------------------------------------------ diagnostics
    private void showInfo(String title, String msg) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void runDiagnostics() {
        diagText.setText("Testing... (about 15 seconds)");
        final String h = host;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder(Diag.report(MainActivity.this, h, true));
            String le = client.lastError();
            sb.append("Last remote error: ").append(le.isEmpty() ? "none" : le).append('\n');
            sb.append("\nRecent log:\n").append(Trace.tail(16));
            final String out = sb.toString();
            runOnUiThread(() -> diagText.setText(out));
        }).start();
    }

    // ------------------------------------------------------------------ status chip
    private void updateChip() {
        int color;
        String t;
        if (pairingActive) { color = WARN; t = "Pairing..."; }
        else if (host.isEmpty()) { color = MUTED; t = "No TV selected"; }
        else {
            int s = client.state();
            if (s == TvClient.READY) { color = GOOD; t = tvName.isEmpty() ? host : tvName; }
            else if (s == TvClient.CONNECTING) { color = WARN; t = "Connecting..."; }
            else if (s == TvClient.FAILED) {
                if ("pair".equals(failKind)) { color = WARN; t = "Needs pairing"; }
                else { color = DANGER; t = "TV not reachable"; }
            }
            else { color = MUTED; t = "Not connected"; }
        }
        chipText.setText(t);
        ((GradientDrawable) chipDot.getBackground()).setColor(color);
    }

    // ------------------------------------------------------------------ key helpers
    private void toast(String m) {
        long now = SystemClock.uptimeMillis();
        if (now - lastToast < 1500) return;
        lastToast = now;
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private void key(int code) {
        if (!client.ready()) {
            toast(host.isEmpty() ? "Choose your TV first" : "TV not connected yet");
            if (!host.isEmpty() && client.state() != TvClient.CONNECTING && !pairingActive) {
                if ("pair".equals(failKind)) beginPairing(host); else client.connect(host);
            }
            return;
        }
        client.key(code);
    }

    private void bindKey(final View v, final int code, final boolean repeat) {
        final Runnable rep = new Runnable() {
            @Override
            public void run() {
                key(code);
                ui.postDelayed(this, 120);
            }
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setPressed(true);
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    key(code);
                    if (repeat) ui.postDelayed(rep, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    ui.removeCallbacks(rep);
                    return true;
                default:
                    return true;
            }
        });
    }

    // ------------------------------------------------------------------ view helpers
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable rect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private Drawable ripple(Drawable bg) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null);
    }

    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private FrameLayout keyCell(int iconRes, int iconTint, Drawable bg, int code, boolean repeat) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(ripple(bg));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        f.addView(iv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        bindKey(f, code, repeat);
        return f;
    }

    private View circleKey(int iconRes, int iconTint, int bgColor, int code, int sizeDp, boolean repeat) {
        FrameLayout f = keyCell(iconRes, iconTint, oval(bgColor), code, repeat);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View spacer() {
        return new View(this);
    }

    // ------------------------------------------------------------------ main UI
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildHeader());
        content = new FrameLayout(this);
        pages[0] = buildRemote();
        pages[1] = buildApps();
        pages[2] = buildKeys();
        pages[3] = buildTvPage();
        for (View v : pages) content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        col.addView(buildNav());
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout h = row();
        h.setPadding(dp(18), dp(14), dp(14), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        h.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView t = text("TV Remote", 20, TEXT, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(10);
        h.addView(t, tl);

        LinearLayout chip = row();
        chip.setPadding(dp(12), dp(8), dp(14), dp(8));
        chip.setBackground(ripple(rect(SURFACE, 20)));
        chipDot = new View(this);
        chipDot.setBackground(oval(MUTED));
        chip.addView(chipDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        chipText = text("", 13, TEXT, false);
        chipText.setSingleLine(true);
        chipText.setMaxWidth(dp(170));
        chipText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = dp(8);
        chip.addView(chipText, cl);
        chip.setOnClickListener(v -> { show(3); startDiscovery(); });
        h.addView(chip);
        return h;
    }

    private View buildNav() {
        LinearLayout n = new LinearLayout(this);
        n.setBackgroundColor(SURFACE);
        n.setPadding(0, dp(6), 0, dp(6));
        int[] icons = {R.drawable.ic_remote, R.drawable.ic_apps, R.drawable.ic_keyboard};
        String[] labels = {"Remote", "Apps", "Keyboard"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(6));
            item.setBackground(ripple(new ColorDrawable(0)));
            navIcons[i] = icon(icons[i], MUTED, 24);
            item.addView(navIcons[i]);
            navLabels[i] = text(labels[i], 11, MUTED, false);
            navLabels[i].setGravity(Gravity.CENTER);
            item.addView(navLabels[i]);
            item.setOnClickListener(v -> show(idx));
            n.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return n;
    }

    private void show(int i) {
        for (int k = 0; k < 4; k++) pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        for (int k = 0; k < 3; k++) {
            int c = k == i ? ACCENT : MUTED;
            navIcons[k].setColorFilter(c);
            navLabels[k].setTextColor(c);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (i == 2) {
            kbInput.requestFocus();
            kbInput.postDelayed(() -> imm.showSoftInput(kbInput, InputMethodManager.SHOW_IMPLICIT), 150);
        } else if (kbInput != null) {
            imm.hideSoftInputFromWindow(kbInput.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------------ Remote page
    private View buildRemote() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(22), dp(6), dp(22), dp(18));

        // top row: power ... input, settings
        LinearLayout top = row();
        top.addView(circleKey(R.drawable.ic_power, 0xFFFFFFFF, 0xFFDC2626, Keys.POWER, 48, false));
        top.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(circleKey(R.drawable.ic_input, TEXT, SURFACE2, Keys.TV_INPUT, 48, false));
        View gap = spacer();
        top.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        top.addView(circleKey(R.drawable.ic_settings, TEXT, SURFACE2, Keys.SETTINGS, 48, false));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // D-pad
        int size = Math.min(getResources().getDisplayMetrics().widthPixels - dp(90), dp(300));
        DPadView pad = new DPadView(this);
        pad.setListener(code -> key(code));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(size, size);
        pl.topMargin = dp(18);
        pl.bottomMargin = dp(18);
        col.addView(pad, pl);

        // back / home / menu
        LinearLayout nav = row();
        int[] ic = {R.drawable.ic_back, R.drawable.ic_home, R.drawable.ic_menu};
        int[] kc = {Keys.BACK, Keys.HOME, Keys.MENU};
        for (int i = 0; i < 3; i++) {
            FrameLayout k = keyCell(ic[i], TEXT, rect(SURFACE2, 28), kc[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
            if (i > 0) lp.leftMargin = dp(12);
            nav.addView(k, lp);
        }
        col.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        // volume rocker | mute, search, info | channel rocker
        LinearLayout mid = row();
        mid.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(18);
        mid.addView(rocker("VOL", R.drawable.ic_add, R.drawable.ic_remove, Keys.VOL_UP, Keys.VOL_DOWN));
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int[] ci = {R.drawable.ic_mute, R.drawable.ic_search, R.drawable.ic_info};
        int[] cc = {Keys.MUTE, Keys.SEARCH, Keys.INFO};
        for (int i = 0; i < 3; i++) {
            View b = circleKey(ci[i], TEXT, SURFACE2, cc[i], 46, false);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(46), dp(46));
            if (i > 0) bl.topMargin = dp(10);
            center.addView(b, bl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        mid.addView(center, cl);
        mid.addView(rocker("CH", R.drawable.ic_chev_up, R.drawable.ic_chev_down, Keys.CH_UP, Keys.CH_DOWN));
        col.addView(mid, ml);

        // media row
        LinearLayout media = row();
        int[] mi = {R.drawable.ic_prev, R.drawable.ic_rewind, R.drawable.ic_play_pause, R.drawable.ic_forward, R.drawable.ic_next};
        int[] mk = {Keys.PREV, Keys.REWIND, Keys.PLAY_PAUSE, Keys.FORWARD, Keys.NEXT};
        for (int i = 0; i < 5; i++) {
            boolean big = i == 2;
            int d = big ? 58 : 46;
            FrameLayout cell = new FrameLayout(this);
            FrameLayout b = keyCell(mi[i], 0xFFFFFFFF, oval(big ? ACCENT : SURFACE2), mk[i], false);
            cell.addView(b, new FrameLayout.LayoutParams(dp(d), dp(d), Gravity.CENTER));
            media.addView(cell, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        LinearLayout.LayoutParams mdl = new LinearLayout.LayoutParams(-1, -2);
        mdl.topMargin = dp(20);
        col.addView(media, mdl);

        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private View rocker(String label, int upIcon, int downIcon, int upCode, int downCode) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setBackground(rect(SURFACE2, 40));
        r.setClipToOutline(true);
        FrameLayout up = keyCell(upIcon, TEXT, new ColorDrawable(0), upCode, true);
        TextView t = text(label, 11, MUTED, true);
        t.setGravity(Gravity.CENTER);
        FrameLayout down = keyCell(downIcon, TEXT, new ColorDrawable(0), downCode, true);
        r.addView(up, new LinearLayout.LayoutParams(dp(78), dp(64)));
        r.addView(t, new LinearLayout.LayoutParams(dp(78), dp(20)));
        r.addView(down, new LinearLayout.LayoutParams(dp(78), dp(64)));
        r.setLayoutParams(new LinearLayout.LayoutParams(dp(78), dp(148)));
        return r;
    }

    // ------------------------------------------------------------------ Apps page
    private View buildApps() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(20));
        col.addView(text("Quick launch", 18, TEXT, true));
        TextView sub = text("Opens the app on your TV (it must be installed there).", 13, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(14));
        col.addView(sub);

        final String[][] apps = {
                {"YouTube", "https://www.youtube.com", "YT", "#E53935"},
                {"Netflix", "https://www.netflix.com/browse", "N", "#B71C1C"},
                {"Prime Video", "https://app.primevideo.com", "P", "#1565C0"},
                {"Hotstar", "https://www.hotstar.com", "H", "#1A237E"},
                {"Spotify", "https://open.spotify.com", "S", "#1DB954"},
                {"YouTube Music", "https://music.youtube.com", "YM", "#D81B60"},
        };
        LinearLayout rowL = null;
        int total = apps.length + 2;
        for (int i = 0; i < total; i++) {
            if (i % 3 == 0) {
                rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(14);
                col.addView(rowL, rp);
            }
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout badge = new FrameLayout(this);
            final int idx = i;
            if (i < apps.length) {
                badge.setBackground(ripple(rect(android.graphics.Color.parseColor(apps[i][3]), 18)));
                TextView l = text(apps[i][2], 22, 0xFFFFFFFF, true);
                l.setGravity(Gravity.CENTER);
                badge.addView(l, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                ((FrameLayout.LayoutParams) l.getLayoutParams()).gravity = Gravity.CENTER;
                tile.addView(badge, new LinearLayout.LayoutParams(dp(68), dp(68)));
                TextView name = text(apps[i][0], 12, TEXT, false);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, dp(6), 0, 0);
                tile.addView(name);
                tile.setOnClickListener(v -> openLink(apps[idx][1]));
            } else {
                boolean settings = i == apps.length;
                badge.setBackground(ripple(rect(SURFACE2, 18)));
                ImageView iv = new ImageView(this);
                iv.setImageResource(settings ? R.drawable.ic_settings : R.drawable.ic_input);
                iv.setColorFilter(TEXT);
                badge.addView(iv, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
                tile.addView(badge, new LinearLayout.LayoutParams(dp(68), dp(68)));
                TextView name = text(settings ? "TV Settings" : "Inputs", 12, TEXT, false);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, dp(6), 0, 0);
                tile.addView(name);
                final int code = settings ? Keys.SETTINGS : Keys.TV_INPUT;
                tile.setOnClickListener(v -> key(code));
            }
            rowL.addView(tile, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        col.addView(text("Open a link on the TV", 16, TEXT, true));
        final EditText link = new EditText(this);
        link.setHint("https://...");
        link.setHintTextColor(MUTED);
        link.setTextColor(TEXT);
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setBackground(rect(SURFACE, 12));
        link.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(8);
        col.addView(link, ll);
        TextView go = text("Open on TV", 15, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(ripple(rect(ACCENT, 14)));
        go.setPadding(0, dp(14), 0, dp(14));
        go.setOnClickListener(v -> {
            String u = link.getText().toString().trim();
            if (!u.isEmpty()) openLink(u);
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(10);
        col.addView(go, gl);

        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private void openLink(String link) {
        if (!client.ready()) { toast("TV not connected yet"); return; }
        client.openLink(link);
        show(0);
    }

    // ------------------------------------------------------------------ Keyboard page
    private View buildKeys() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(20));
        col.addView(text("Keyboard", 18, TEXT, true));
        TextView sub = text("Whatever you type appears in the TV's open text box (search, sign-in...). Open the box on the TV first.", 13, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(14));
        col.addView(sub);

        kbInput = new EditText(this);
        kbInput.setHint("Type here");
        kbInput.setHintTextColor(MUTED);
        kbInput.setTextColor(TEXT);
        kbInput.setTextSize(18);
        kbInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        kbInput.setBackground(rect(SURFACE, 14));
        kbInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        kbInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                if (kbSilent) return;
                String now = e.toString();
                if (!client.ready()) { kbLast = now; return; }
                if (now.startsWith(kbLast)) {
                    client.type(now.substring(kbLast.length()));
                } else if (kbLast.startsWith(now)) {
                    for (int i = 0; i < kbLast.length() - now.length(); i++) client.key(Keys.DEL);
                } else {
                    for (int i = 0; i < kbLast.length(); i++) client.key(Keys.DEL);
                    client.type(now);
                }
                kbLast = now;
            }
        });
        col.addView(kbInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r = row();
        int[] ic = {R.drawable.ic_backspace, R.drawable.ic_enter, R.drawable.ic_search};
        int[] kc = {Keys.DEL, Keys.ENTER, Keys.SEARCH};
        for (int i = 0; i < 3; i++) {
            FrameLayout k = keyCell(ic[i], TEXT, rect(SURFACE2, 14), kc[i], i == 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            if (i > 0) lp.leftMargin = dp(10);
            r.addView(k, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(14);
        col.addView(r, rl);

        TextView clear = text("Clear text box", 14, MUTED, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(0, dp(16), 0, dp(8));
        clear.setOnClickListener(v -> {
            kbSilent = true;
            kbInput.setText("");
            kbSilent = false;
            kbLast = "";
        });
        col.addView(clear);
        return col;
    }

    private String modeLabel() {
        int m = TvClient.mode(this);
        String n = m == TvClient.MODE_ANDROID_TV ? "Android TV (6-character code)"
                : m == TvClient.MODE_XIAOMI ? "Xiaomi Mi TV (no code)" : "Automatic";
        return "Connection type: " + n + "  (tap to change)";
    }

    // ------------------------------------------------------------------ TV selection page
    private View buildTvPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(20));
        col.addView(text("Choose your TV", 20, TEXT, true));
        scanStatus = text("", 13, MUTED, false);
        scanStatus.setPadding(0, dp(4), 0, dp(14));
        col.addView(scanStatus);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        col.addView(deviceList, new LinearLayout.LayoutParams(-1, -2));

        TextView scan = text("Search again", 15, 0xFFFFFFFF, true);
        scan.setGravity(Gravity.CENTER);
        scan.setBackground(ripple(rect(ACCENT, 14)));
        scan.setPadding(0, dp(14), 0, dp(14));
        scan.setOnClickListener(v -> startDiscovery());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(8);
        col.addView(scan, sl);

        TextView manual = text("Enter IP address", 15, TEXT, true);
        manual.setGravity(Gravity.CENTER);
        manual.setBackground(ripple(rect(SURFACE2, 14)));
        manual.setPadding(0, dp(14), 0, dp(14));
        manual.setOnClickListener(v -> askIp());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(10);
        col.addView(manual, mlp);

        TextView test = text("Test connection", 15, TEXT, true);
        test.setGravity(Gravity.CENTER);
        test.setBackground(ripple(rect(SURFACE2, 14)));
        test.setPadding(0, dp(14), 0, dp(14));
        test.setOnClickListener(v -> runDiagnostics());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        col.addView(test, tlp);
        final TextView modeRow = text(modeLabel(), 15, TEXT, true);
        modeRow.setGravity(Gravity.CENTER);
        modeRow.setBackground(ripple(rect(SURFACE2, 14)));
        modeRow.setPadding(0, dp(14), 0, dp(14));
        modeRow.setOnClickListener(v -> {
            int m = (TvClient.mode(this) + 1) % 3;
            TvClient.saveMode(this, m);
            modeRow.setText(modeLabel());
            toast("Connection type changed");
            if (!host.isEmpty() && !pairingActive) {
                failKind = "";
                client.connect(host);
            }
        });
        LinearLayout.LayoutParams mdlp = new LinearLayout.LayoutParams(-1, -2);
        mdlp.topMargin = dp(10);
        col.addView(modeRow, mdlp);
        diagText = text("", 12, MUTED, false);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setPadding(dp(4), dp(12), dp(4), 0);
        diagText.setTextIsSelectable(true);
        col.addView(diagText);

        TextView pairAgain = text("Pair with the selected TV again", 14, MUTED, false);
        pairAgain.setGravity(Gravity.CENTER);
        pairAgain.setPadding(0, dp(18), 0, dp(6));
        pairAgain.setOnClickListener(v -> {
            if (host.isEmpty()) { toast("Choose your TV first"); return; }
            show(0);
            beginPairing(host);
        });
        col.addView(pairAgain);

        TextView hint = text("Automatic mode picks the TV's own remote service. Android TV / Google TV devices show a "
                + "6-character code the first time. Xiaomi Mi TVs without that service connect directly. "
                + "If one type does not work, tap Connection type to try another.", 12, MUTED, false);
        hint.setPadding(0, dp(14), 0, 0);
        col.addView(hint);

        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }
}
