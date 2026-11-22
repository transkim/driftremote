package app.driftremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

import app.driftremote.core.Keys;
import app.driftremote.core.Pairing;
import app.driftremote.core.RemoteSession;

/** The remote control screen for one TV, including the pairing flow. */
public class RemoteActivity extends Activity implements TvConnection.Ui {
    private String host;
    private String name;
    private TvConnection conn;
    private Presser presser;
    private TextView status;
    private ImageButton power;
    private DpadView dpad;
    private TouchpadView touchpad;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String volumeText = "";
    private boolean tvOn = true;
    private boolean pairingShown;
    private long lastNotConnectedToast;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_remote);
        Ui.fitSystemBars(findViewById(R.id.root));
        host = getIntent().getStringExtra("host");
        name = getIntent().getStringExtra("name");
        ((TextView) findViewById(R.id.tvName)).setText(name);
        status = findViewById(R.id.tvStatus);
        power = findViewById(R.id.power);
        dpad = findViewById(R.id.dpad);
        touchpad = findViewById(R.id.touchpad);
        presser = new Presser(this::sendKey);

        findViewById(R.id.close).setOnClickListener(v -> finish());
        findViewById(R.id.tvName).setOnClickListener(v -> Ui.showAbout(this));

        presser.bind(power, Keys.POWER, false);
        presser.bind(findViewById(R.id.input), Keys.TV_INPUT, false);
        presser.bind(findViewById(R.id.settings), Keys.SETTINGS, false);
        presser.bind(findViewById(R.id.back), Keys.BACK, false);
        presser.bind(findViewById(R.id.home), Keys.HOME, false);
        presser.bind(findViewById(R.id.menu), Keys.MENU, false);
        presser.bind(findViewById(R.id.volDown), Keys.VOLUME_DOWN, true);
        presser.bind(findViewById(R.id.volUp), Keys.VOLUME_UP, true);
        presser.bind(findViewById(R.id.mute), Keys.VOLUME_MUTE, false);
        presser.bind(findViewById(R.id.rewind), Keys.MEDIA_REWIND, false);
        presser.bind(findViewById(R.id.playPause), Keys.MEDIA_PLAY_PAUSE, false);
        presser.bind(findViewById(R.id.ff), Keys.MEDIA_FAST_FORWARD, false);
        findViewById(R.id.keyboard).setOnClickListener(v -> showKeyboard());
        findViewById(R.id.more).setOnClickListener(v -> showMore());

        dpad.setListener(new DpadView.Listener() {
            @Override public void onKeyDown(int key) { presser.down(key, key != Keys.DPAD_CENTER); }
            @Override public void onKeyUp(int key) { presser.up(); }
        });
        touchpad.setListener(new TouchpadView.Listener() {
            @Override public void onMove(int key) { sendKey(key, RemoteSession.SHORT); }
            @Override public void onTap() { sendKey(Keys.DPAD_CENTER, RemoteSession.SHORT); }
            @Override public void onHoldStart() { sendKey(Keys.DPAD_CENTER, RemoteSession.START_LONG); }
            @Override public void onHoldEnd() { sendKey(Keys.DPAD_CENTER, RemoteSession.END_LONG); }
        });
        setupMouse();
        setPadMode(getSharedPreferences("driftremote", MODE_PRIVATE).getInt("padMode",
                getSharedPreferences("driftremote", MODE_PRIVATE).getBoolean("touchpad", false) ? 1 : 0));
        findViewById(R.id.mode).setOnClickListener(v -> {
            CharSequence[] names = {"D-pad", "Swipe pad", "Mouse pointer"};
            new AlertDialog.Builder(this).setTitle("Control with")
                    .setSingleChoiceItems(names, padMode, (d, which) -> {
                        d.dismiss();
                        setPadMode(which);
                        getSharedPreferences("driftremote", MODE_PRIVATE).edit().putInt("padMode", which).apply();
                    }).show();
        });

        buildApps();
        conn = new TvConnection(host, this);
    }

    private int padMode;

    /** 0 = d-pad, 1 = swipe pad, 2 = mouse pointer */
    private void setPadMode(int m) {
        padMode = m;
        dpad.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
        touchpad.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        mousePanel.setVisibility(m == 2 ? View.VISIBLE : View.GONE);
        ((ImageButton) findViewById(R.id.mode)).setImageResource(
                m == 0 ? R.drawable.ic_dpad : m == 1 ? R.drawable.ic_touch : R.drawable.ic_mouse);
        if (m == 2 && started) mouse.start();
        else mouse.stop();
    }

    // ---- mouse pointer ----

    private View mousePanel;
    private MouseView mousePad;
    private MouseLink mouse;
    private Button mouseSetup;
    private boolean started;
    private AlertDialog pinDialog;
    private ApkServer apkServer;
    private static final float[] SPEEDS = {0.6f, 1f, 1.6f};
    private static final String[] SPEED_NAMES = {"Slow", "Normal", "Fast"};

    private void setupMouse() {
        mousePanel = findViewById(R.id.mousePanel);
        mousePad = findViewById(R.id.mousePad);
        mouseSetup = findViewById(R.id.mouseSetup);
        mouse = new MouseLink(this, host, this::onMouseState);
        mousePad.setListener(new MouseView.Listener() {
            private app.driftremote.core.MouseClient c() { return mouse.client(); }
            @Override public void move(float dx, float dy) { if (c() != null) c().move(dx, dy); }
            @Override public void click() { if (c() != null) c().click(); else mouseNotReady(); }
            @Override public void longPress() { if (c() != null) c().longPress(); }
            @Override public void dragStart() { if (c() != null) c().dragStart(); }
            @Override public void dragMove(float dx, float dy) { if (c() != null) c().dragMove(dx, dy); }
            @Override public void dragEnd() { if (c() != null) c().dragEnd(); }
            @Override public void scrollStart() { if (c() != null) c().scrollStart(); }
            @Override public void scrollMove(float dx, float dy) { if (c() != null) c().scrollMove(dx, dy); }
            @Override public void scrollEnd() { if (c() != null) c().scrollEnd(); }
            @Override public void back() { sendKey(Keys.BACK, RemoteSession.SHORT); }
        });
        findViewById(R.id.mouseClick).setOnClickListener(v -> {
            if (mouse.client() != null) mouse.client().click(); else mouseNotReady();
        });
        findViewById(R.id.mouseHold).setOnClickListener(v -> {
            if (mouse.client() != null) mouse.client().longPress(); else mouseNotReady();
        });
        Button speedBtn = findViewById(R.id.mouseSpeed);
        int sp = getSharedPreferences("driftremote", MODE_PRIVATE).getInt("mouseSpeed", 1);
        mousePad.setSpeed(SPEEDS[sp]);
        speedBtn.setText("Speed: " + SPEED_NAMES[sp]);
        speedBtn.setOnClickListener(v -> {
            int n = (getSharedPreferences("driftremote", MODE_PRIVATE).getInt("mouseSpeed", 1) + 1) % 3;
            getSharedPreferences("driftremote", MODE_PRIVATE).edit().putInt("mouseSpeed", n).apply();
            mousePad.setSpeed(SPEEDS[n]);
            speedBtn.setText("Speed: " + SPEED_NAMES[n]);
        });
        mouseSetup.setOnClickListener(v -> showMouseSetup());
    }

    private void mouseNotReady() {
        long now = System.currentTimeMillis();
        if (now - lastNotConnectedToast > 2500) {
            lastNotConnectedToast = now;
            Toast.makeText(this, "The mouse isn't connected yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void onMouseState(MouseLink.State s) {
        mouseSetup.setVisibility(s == MouseLink.State.NOT_SET_UP ? View.VISIBLE : View.GONE);
        switch (s) {
            case CONNECTING:
                if (mouse.client() == null) mousePad.setMessage("Connecting the mouse…");
                break;
            case READY:
                mousePad.setMessage(null);
                if (pinDialog != null) {
                    pinDialog.dismiss();
                    pinDialog = null;
                }
                break;
            case NEED_PIN:
            case WRONG_PIN:
                mousePad.setMessage("Type the code shown on the TV");
                askMousePin(s == MouseLink.State.WRONG_PIN);
                break;
            case NOT_SET_UP:
                mousePad.setMessage("The mouse needs a one-time setup on the TV.\nTap the button below.");
                if (pinDialog != null) {
                    pinDialog.dismiss();
                    pinDialog = null;
                }
                break;
            case OFFLINE:
                mousePad.setMessage("Can't reach the TV mouse. Retrying…");
                break;
        }
    }

    private void askMousePin(boolean wrong) {
        if (pinDialog != null) pinDialog.dismiss();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setTextSize(26);
        input.setGravity(Gravity.CENTER);
        input.setLetterSpacing(0.3f);
        pinDialog = new AlertDialog.Builder(this)
                .setTitle("Connect the mouse")
                .setMessage((wrong ? "That code didn't match. " : "") + "Type the 4-digit code shown on the TV.")
                .setView(pad(input))
                .setPositiveButton("Connect", (d, w) -> mouse.sendPin(input.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> setPadMode(0))
                .setCancelable(false)
                .create();
        pinDialog.setOnShowListener(d -> {
            input.requestFocus();
            pinDialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        pinDialog.show();
    }

    private void showMouseSetup() {
        if (apkServer == null) apkServer = new ApkServer(this);
        apkServer.start();
        String url = ApkServer.url();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(this);
        t1.setTextColor(Ui.color(this, R.color.text));
        t1.setTextSize(15);
        t1.setText("The mouse pointer needs the small Driftremote TV app on the TV. This is a one-time setup.\n\n"
                + "1. Install the free \"Downloader\" app on the TV.");
        box.addView(t1);
        box.addView(smallButton("Open Downloader in the TV's Play Store", v ->
                sendLink("market://details?id=com.esaba.downloader")));

        TextView t2 = new TextView(this);
        t2.setTextColor(Ui.color(this, R.color.text));
        t2.setTextSize(15);
        t2.setText("\n2. Open Downloader on the TV, select its address box, and type this address "
                + "(or tap \"Type it on the TV\"). Then choose Go, and Install. "
                + "Allow Downloader to install apps if the TV asks.\n");
        box.addView(t2);
        TextView link = new TextView(this);
        link.setTextColor(Ui.color(this, R.color.accent));
        link.setTextSize(18);
        link.setTextIsSelectable(true);
        link.setText(url == null ? "Connect this phone to Wi-Fi first" : url);
        box.addView(link);
        if (url != null) box.addView(smallButton("Type it on the TV", v -> typeText(url)));

        TextView t3 = new TextView(this);
        t3.setTextColor(Ui.color(this, R.color.text));
        t3.setTextSize(15);
        t3.setText("\n3. Open Driftremote TV on the TV and choose \"Turn on the mouse\". "
                + "In Accessibility, open \"Driftremote mouse\" and switch it on.");
        box.addView(t3);
        box.addView(smallButton("Open Driftremote TV on the TV", v -> sendLink("market://launch?id=app.driftremote.tv")));

        TextView t4 = new TextView(this);
        t4.setTextColor(Ui.color(this, R.color.muted));
        t4.setTextSize(14);
        t4.setText("\n4. Come back here. The mouse connects by itself, and the TV shows a code to type once. "
                + "Keep this window open until the TV has downloaded the app.");
        box.addView(t4);

        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof Button) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (44 * d));
                lp.topMargin = (int) (8 * d);
                c.setLayoutParams(lp);
            }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(pad(box));
        new AlertDialog.Builder(this).setTitle("Set up the mouse on your TV").setView(sv)
                .setPositiveButton("Done", null)
                .setOnDismissListener(di -> {
                    if (apkServer != null) apkServer.stop();
                })
                .show();
    }

    private void sendLink(String link) {
        if (!conn.send(s -> s.openLink(link))) notConnected();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (!pairingShown) conn.start();
        if (padMode == 2) mouse.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        presser.cancel();
        conn.stop();
        started = false;
        mouse.stop();
        if (apkServer != null) apkServer.stop();
    }

    // ---- connection callbacks ----

    @Override
    public void onState(TvConnection.State state, String detail) {
        switch (state) {
            case CONNECTING:
                if (!conn.isConnected()) status.setText("Connecting…");
                break;
            case CONNECTED:
                App.get().store().save(host, null, true, false);
                updateStatus();
                break;
            case DISCONNECTED:
                status.setText(detail == null ? "Disconnected. Retrying…" : detail + " Retrying…");
                break;
            case NEEDS_PAIRING:
                App.get().store().save(host, null, false, false);
                startPairing();
                break;
        }
    }

    @Override
    public void onPower(boolean on) {
        tvOn = on;
        updateStatus();
    }

    @Override
    public void onVolume(int level, int max, boolean muted) {
        volumeText = muted ? "Muted" : (max > 0 ? "Volume " + level : "");
        updateStatus();
    }

    @Override
    public void onTextField(boolean active, String text) {
        tvFieldText = active && text != null ? text : "";
        if (active && keyboardDialog == null && hasWindowFocus()) {
            showKeyboard(); // the TV asked for text, like Google's remote does
        } else {
            updateKeyboardNote(false);
        }
    }

    private void updateStatus() {
        if (!conn.isConnected()) return;
        String s = tvOn ? "Connected" : "Connected · TV screen is off";
        if (!volumeText.isEmpty()) s += " · " + volumeText;
        status.setText(s);
    }

    private void sendKey(int code, int direction) {
        if (!conn.key(code, direction)) notConnected();
    }

    private void notConnected() {
        long now = System.currentTimeMillis();
        if (now - lastNotConnectedToast > 2500) {
            lastNotConnectedToast = now;
            Toast.makeText(this, "Not connected to the TV yet", Toast.LENGTH_SHORT).show();
        }
    }

    /** Phone volume keys control the TV while this screen is open. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && conn.isConnected()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                sendKey(k == KeyEvent.KEYCODE_VOLUME_UP ? Keys.VOLUME_UP : Keys.VOLUME_DOWN, RemoteSession.SHORT);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- pairing ----

    private void startPairing() {
        if (pairingShown || isFinishing()) return;
        pairingShown = true;
        conn.stop();
        status.setText("Pairing…");
        AlertDialog waiting = new AlertDialog.Builder(this)
                .setTitle("Pair with " + name)
                .setMessage("Asking the TV to show a code…")
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setCancelable(false)
                .show();
        new Thread(() -> {
            Pairing p = null;
            try {
                p = new Pairing(host, Pairing.PORT, App.get().identity(), App.phoneName());
                p.start();
                Pairing ready = p;
                main.post(() -> {
                    waiting.dismiss();
                    askCode(ready, null);
                });
            } catch (Exception e) {
                if (p != null) p.close();
                String msg = e instanceof IOException ? TvConnection.friendly((IOException) e) : String.valueOf(e.getMessage());
                main.post(() -> {
                    waiting.dismiss();
                    pairingFailed(msg);
                });
            }
        }, "pairing").start();
    }

    private void askCode(Pairing p, String error) {
        if (isFinishing()) {
            p.close();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("Code on the TV");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        input.setTextSize(26);
        input.setGravity(Gravity.CENTER);
        input.setLetterSpacing(0.3f);
        String msg = "Type the 6-character code shown on your TV.";
        if (error != null) msg = error + "\n\n" + msg;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Pair with " + name)
                .setMessage(msg)
                .setView(pad(input))
                .setPositiveButton("Pair", null)
                .setNegativeButton("Cancel", (d, w) -> {
                    p.close();
                    finish();
                })
                .setCancelable(false)
                .create();
        dlg.setOnShowListener(d -> {
            Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String code = input.getText().toString().trim();
                if (code.length() != 6) {
                    input.setError("6 characters");
                    return;
                }
                ok.setEnabled(false);
                new Thread(() -> {
                    try {
                        p.finish(code);
                        p.close();
                        main.post(() -> {
                            dlg.dismiss();
                            pairingShown = false;
                            App.get().store().save(host, null, true, true);
                            Toast.makeText(this, "Paired with " + name, Toast.LENGTH_SHORT).show();
                            conn.start();
                        });
                    } catch (Pairing.WrongCodeException e) {
                        // A wrong local check keeps the TV session open; let them retype.
                        main.post(() -> {
                            ok.setEnabled(true);
                            input.setError(e.getMessage());
                        });
                    } catch (IOException e) {
                        p.close();
                        main.post(() -> {
                            dlg.dismiss();
                            pairingShown = false;
                            pairingFailed("Pairing didn't finish: " + TvConnection.friendly(e));
                        });
                    }
                }, "pairing-finish").start();
            });
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dlg.show();
    }

    private void pairingFailed(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Couldn't pair")
                .setMessage(msg + "\n\nMake sure the TV is on and on the same Wi-Fi as this phone.")
                .setPositiveButton("Try again", (d, w) -> {
                    pairingShown = false;
                    startPairing();
                })
                .setNegativeButton("Close", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ---- keyboard ----

    private AlertDialog keyboardDialog;
    private EditText keyboardInput;
    private TextView keyboardNote;
    private boolean keyboardSyncing;
    private String tvFieldText = "";

    private void showKeyboard() {
        if (!conn.isConnected()) {
            notConnected();
            return;
        }
        if (keyboardDialog != null && keyboardDialog.isShowing()) return;
        EditText input = new EditText(this);
        input.setHint("Type here");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x99FFFFFF);
        input.setTextSize(20);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setSingleLine(true);

        TextView note = new TextView(this);
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(14);
        note.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.addView(smallButton("⌫ Delete", v -> {
            if (liveTyping()) {
                String t = input.getText().toString();
                if (!t.isEmpty()) input.setText(t.substring(0, t.length() - 1));
                input.setSelection(input.getText().length());
            } else {
                sendKey(Keys.DEL, RemoteSession.SHORT);
            }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        keys.addView(smallButton("Search ↵", v -> sendKey(Keys.ENTER, RemoteSession.SHORT)), new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        box.addView(note, new LinearLayout.LayoutParams(-1, -2));
        box.addView(keys, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Type on the TV")
                .setView(pad(box))
                .setPositiveButton("Send", null)
                .setNegativeButton("Close", null)
                .create();
        keyboardDialog = dlg;
        keyboardInput = input;
        keyboardNote = note;
        updateKeyboardNote(false);

        if (liveTyping() && !tvFieldText.isEmpty()) {
            keyboardSyncing = true;
            input.setText(tvFieldText);
            input.setSelection(tvFieldText.length());
            keyboardSyncing = false;
        }
        // While the TV has a text field open, every change is mirrored to it right away.
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (keyboardSyncing || !liveTyping()) return;
                String t = e.toString();
                conn.send(s -> s.text(t));
                updateKeyboardNote(true);
            }
        });
        Runnable send = () -> {
            String text = input.getText().toString();
            if (liveTyping()) {
                // Text is already on the TV; Send submits it.
                sendKey(Keys.ENTER, RemoteSession.SHORT);
                note.setText("Sent “" + text + "” to the TV.");
                return;
            }
            if (text.isEmpty()) return;
            typeText(text);
            note.setText("Sent “" + text + "” as key presses.");
            keyboardSyncing = true;
            input.setText("");
            keyboardSyncing = false;
        };
        input.setOnEditorActionListener((v, id, ev) -> {
            send.run();
            return true;
        });
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> send.run());
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dlg.setOnDismissListener(d -> {
            if (keyboardDialog == dlg) {
                keyboardDialog = null;
                keyboardInput = null;
                keyboardNote = null;
            }
        });
        dlg.show();
    }

    private boolean liveTyping() {
        return conn.supportsText() && conn.hasTextField();
    }

    private void updateKeyboardNote(boolean typed) {
        if (keyboardNote == null) return;
        if (liveTyping()) {
            keyboardNote.setText(typed ? "Typing on the TV…" : "The TV's text box is ready. What you type appears there as you go.");
        } else {
            keyboardNote.setText("Select a search box on the TV first. Until then, Send types it as key presses.");
        }
    }

    /** Sends text through the TV's input method when a field is active, else as key presses. */
    private void typeText(String text) {
        if (conn.supportsText() && conn.hasTextField()) {
            conn.send(s -> s.text(text));
            return;
        }
        conn.send(s -> {
            for (char c : text.toCharArray()) {
                int k = Keys.forChar(c);
                if (k == 0) continue;
                if (Character.isUpperCase(c)) {
                    s.key(59, RemoteSession.START_LONG); // shift
                    s.key(k, RemoteSession.SHORT);
                    s.key(59, RemoteSession.END_LONG);
                } else {
                    s.key(k, RemoteSession.SHORT);
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    // ---- more buttons ----

    private void showMore() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        AlertDialog[] holder = new AlertDialog[1];
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Info", "0", "Guide",
                "CH +", "CH −", "Subtitles", "Red", "Green", "Yellow", "Blue", "Previous", "Next",
                "Recent apps", "All apps", "Search"};
        int[] codes = {8, 9, 10, 11, 12, 13, 14, 15, 16, Keys.INFO, 7, Keys.GUIDE,
                Keys.CHANNEL_UP, Keys.CHANNEL_DOWN, Keys.CAPTIONS, Keys.PROG_RED, Keys.PROG_GREEN, Keys.PROG_YELLOW,
                Keys.PROG_BLUE, Keys.MEDIA_PREVIOUS, Keys.MEDIA_NEXT, Keys.APP_SWITCH, Keys.ALL_APPS, Keys.SEARCH};
        for (int i = 0; i < labels.length; i++) {
            Button b = smallButton(labels[i], null);
            presser.bind(b, codes[i], codes[i] == Keys.CHANNEL_UP || codes[i] == Keys.CHANNEL_DOWN);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 92);
            lp.height = Ui.dp(this, 48);
            int m = Ui.dp(this, 4);
            lp.setMargins(m, m, m, m);
            grid.addView(b, lp);
        }
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(grid);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        holder[0] = new AlertDialog.Builder(this).setTitle("More buttons").setView(sv)
                .setNeutralButton("About", (d, w) -> Ui.showAbout(this))
                .setNegativeButton("Close", null).show();
    }

    // ---- app shortcuts ----

    private void buildApps() {
        LinearLayout apps = findViewById(R.id.apps);
        apps.removeAllViews();
        List<TvStore.Shortcut> list = App.get().store().shortcuts();
        for (int i = 0; i < list.size(); i++) {
            TvStore.Shortcut s = list.get(i);
            int index = i;
            Button b = chip(s.label);
            b.setOnClickListener(v -> {
                if (!conn.send(x -> x.openLink(s.link))) notConnected();
            });
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(s.label)
                        .setItems(new CharSequence[]{"Remove shortcut"}, (d, w) -> {
                            List<TvStore.Shortcut> l = App.get().store().shortcuts();
                            l.remove(index);
                            App.get().store().setShortcuts(l);
                            buildApps();
                        }).show();
                return true;
            });
            apps.addView(b);
        }
        Button add = chip("+ Add app");
        add.setOnClickListener(v -> addShortcut());
        apps.addView(add);
    }

    private void addShortcut() {
        EditText label = new EditText(this);
        label.setHint("Name, e.g. Shahid");
        EditText link = new EditText(this);
        link.setHint("Package name or link, e.g. net.mbc.shahid");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label);
        box.addView(link);
        new AlertDialog.Builder(this)
                .setTitle("Add an app shortcut")
                .setMessage("Find the package name in the app's Google Play web address (the part after id=).")
                .setView(pad(box))
                .setPositiveButton("Add", (d, w) -> {
                    String l = label.getText().toString().trim();
                    String k = link.getText().toString().trim();
                    if (l.isEmpty() || k.isEmpty()) return;
                    List<TvStore.Shortcut> list = App.get().store().shortcuts();
                    list.add(new TvStore.Shortcut(l, TvStore.toLink(k)));
                    App.get().store().setShortcuts(list);
                    buildApps();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- small view helpers ----

    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Ui.color(this, R.color.text));
        b.setTextSize(14);
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
        lp.rightMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Ui.color(this, R.color.text));
        b.setBackgroundResource(R.drawable.bg_chip);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private View pad(View v) {
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }
}
