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
        host = getIntent().getStringExtra("host");
        name = getIntent().getStringExtra("name");
        ((TextView) findViewById(R.id.tvName)).setText(name);
        status = findViewById(R.id.tvStatus);
        power = findViewById(R.id.power);
        dpad = findViewById(R.id.dpad);
        touchpad = findViewById(R.id.touchpad);
        presser = new Presser(this::sendKey);

        findViewById(R.id.close).setOnClickListener(v -> finish());

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
        boolean useTouchpad = getSharedPreferences("driftremote", MODE_PRIVATE).getBoolean("touchpad", false);
        setTouchpad(useTouchpad);
        findViewById(R.id.mode).setOnClickListener(v -> {
            boolean now = touchpad.getVisibility() != View.VISIBLE;
            setTouchpad(now);
            getSharedPreferences("driftremote", MODE_PRIVATE).edit().putBoolean("touchpad", now).apply();
        });

        buildApps();
        conn = new TvConnection(host, this);
    }

    private void setTouchpad(boolean on) {
        touchpad.setVisibility(on ? View.VISIBLE : View.GONE);
        dpad.setVisibility(on ? View.GONE : View.VISIBLE);
        ((ImageButton) findViewById(R.id.mode)).setImageResource(on ? R.drawable.ic_dpad : R.drawable.ic_touch);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!pairingShown) conn.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        presser.cancel();
        conn.stop();
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
    public void onTextField(boolean active, String text) {}

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

    private void showKeyboard() {
        if (!conn.isConnected()) {
            notConnected();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("Type here, then Send");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setSingleLine(true);

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.addView(smallButton("⌫ Delete", v -> sendKey(Keys.DEL, RemoteSession.SHORT)), new LinearLayout.LayoutParams(0, -2, 1));
        keys.addView(smallButton("Search ↵", v -> sendKey(Keys.ENTER, RemoteSession.SHORT)), new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        box.addView(keys, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Type on the TV")
                .setMessage("Select a search box or text field on the TV first.")
                .setView(pad(box))
                .setPositiveButton("Send", null)
                .setNegativeButton("Close", null)
                .create();
        Runnable send = () -> {
            String text = input.getText().toString();
            if (text.isEmpty()) return;
            typeText(text);
            input.setText("");
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
        dlg.show();
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
