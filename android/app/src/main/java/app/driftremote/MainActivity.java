package app.driftremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lists TVs found on the Wi-Fi plus the ones used before. */
public class MainActivity extends Activity implements Discovery.Listener {
    private Discovery discovery;
    private LinearLayout list;
    private TextView status;
    /** host -> name of TVs seen on the network right now */
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Map<String, String> nameToHost = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        Ui.fitSystemBars(findViewById(R.id.root));
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        ((TextView) findViewById(R.id.credit)).setText(
                "Developed by " + getString(R.string.developer) + "  ·  Version " + BuildConfig.VERSION_NAME);
        discovery = new Discovery(this, this);
        findViewById(R.id.add).setOnClickListener(v -> askForAddress());
        findViewById(R.id.refresh).setOnClickListener(v -> {
            discovery.stop();
            found.clear();
            nameToHost.clear();
            render();
            discovery.start();
        });

        // Opening the app goes straight to the last TV, like a real remote.
        if (saved == null && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            for (TvStore.Tv t : App.get().store().tvs()) {
                if (t.paired) {
                    open(t.host, t.name);
                    break;
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        discovery.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        discovery.stop();
    }

    @Override
    public void onFound(String name, String host) {
        String old = nameToHost.put(name, host);
        if (old != null && !old.equals(host)) found.remove(old);
        found.put(host, name);
        // Keep the saved TV's address current if its IP changed (DHCP).
        TvStore store = App.get().store();
        if (store.find(host) == null) {
            for (TvStore.Tv t : store.tvs()) {
                if (t.name.equals(name) && !t.host.equals(host)) {
                    store.forget(t.host);
                    store.save(host, name, t.paired, false);
                }
            }
        }
        render();
    }

    @Override
    public void onLost(String name) {
        String host = nameToHost.remove(name);
        if (host != null) found.remove(host);
        render();
    }

    private void render() {
        list.removeAllViews();
        Map<String, String[]> rows = new LinkedHashMap<>(); // host -> {name, subtitle}
        for (TvStore.Tv t : App.get().store().tvs()) {
            boolean here = found.containsKey(t.host);
            String sub = (t.paired ? "Paired" : "Not paired yet") + (here ? " · On this Wi-Fi" : "") + " · " + t.host;
            rows.put(t.host, new String[]{t.name, sub, "saved"});
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (!rows.containsKey(e.getKey())) {
                rows.put(e.getKey(), new String[]{e.getValue(), "Tap to pair · " + e.getKey(), "new"});
            }
        }
        for (Map.Entry<String, String[]> e : rows.entrySet()) {
            list.addView(row(e.getKey(), e.getValue()[0], e.getValue()[1], "saved".equals(e.getValue()[2])));
        }
        if (found.isEmpty()) {
            status.setText(rows.isEmpty()
                    ? "Looking for TVs on your Wi-Fi… Make sure the TV is on."
                    : "Looking for TVs on your Wi-Fi…");
        } else {
            status.setText(found.size() == 1 ? "1 TV found on your Wi-Fi" : found.size() + " TVs found on your Wi-Fi");
        }
    }

    private View row(String host, String name, String subtitle, boolean saved) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        int p = (int) (16 * d);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (10 * d);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_tv);
        icon.setColorFilter(Ui.color(this, R.color.accent));
        row.addView(icon, new LinearLayout.LayoutParams((int) (32 * d), (int) (32 * d)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = (int) (14 * d);
        TextView t1 = new TextView(this);
        t1.setText(name);
        t1.setTextSize(17);
        t1.setTextColor(Ui.color(this, R.color.text));
        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextSize(13);
        t2.setTextColor(Ui.color(this, R.color.muted));
        texts.addView(t1);
        texts.addView(t2);
        row.addView(texts, tl);

        row.setOnClickListener(v -> open(host, name));
        if (saved) {
            row.setOnLongClickListener(v -> {
                manage(host, name);
                return true;
            });
        }
        return row;
    }

    private void manage(String host, String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new CharSequence[]{"Rename", "Forget this TV"}, (dlg, which) -> {
                    if (which == 0) {
                        EditText input = new EditText(this);
                        input.setText(name);
                        input.setSelectAllOnFocus(true);
                        new AlertDialog.Builder(this).setTitle("Rename TV").setView(pad(input))
                                .setPositiveButton("Save", (d2, w) -> {
                                    App.get().store().save(host, input.getText().toString().trim(), null, false);
                                    render();
                                })
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        App.get().store().forget(host);
                        render();
                    }
                }).show();
    }

    private void askForAddress() {
        EditText input = new EditText(this);
        input.setHint("e.g. 192.168.1.40");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Add a TV")
                .setMessage("Find the TV's IP address on the TV in Settings > Network & Internet > (your Wi-Fi).")
                .setView(pad(input))
                .setPositiveButton("Connect", (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (!host.isEmpty()) open(host, found.containsKey(host) ? found.get(host) : "TV at " + host);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View pad(View v) {
        LinearLayout box = new LinearLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void open(String host, String name) {
        TvStore.Tv saved = App.get().store().find(host);
        App.get().store().save(host, saved == null ? name : null, null, true);
        startActivity(new Intent(this, RemoteActivity.class)
                .putExtra("host", host)
                .putExtra("name", saved == null ? name : saved.name));
    }
}
