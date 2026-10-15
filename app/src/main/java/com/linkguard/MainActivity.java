package com.linkguard;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings screen: make LinkGuard the default browser, pick the real browser, test a link. */
public class MainActivity extends AppCompatActivity {

    private static final Uri PROBE = Uri.parse("https://example.com");
    private static final Uri RELEASES = Uri.parse("https://github.com/AugerFlare/linkguard/releases/latest");
    private RadioGroup browsers;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        browsers = findViewById(R.id.browsers);
        findViewById(R.id.setDefault).setOnClickListener(x -> requestDefault());
        MaterialSwitch enabled = findViewById(R.id.enabled);
        enabled.setOnCheckedChangeListener((x, checked) -> {
            prefs(this).edit().putBoolean("enabled", checked).apply();
            showStatus(isDefault(), checked);
            StatusNotification.update(this);
        });
        MaterialSwitch statusNotification = findViewById(R.id.statusNotification);
        statusNotification.setChecked(StatusNotification.shown(this));
        statusNotification.setOnCheckedChangeListener((x, checked) -> {
            if (checked && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            StatusNotification.setShown(this, checked);
        });

        // No INTERNET permission, so no automatic check: show our version and open the releases page to compare.
        MaterialButton updates = findViewById(R.id.updates);
        try {
            updates.setText("Version " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                    + " · Check for updates");
        } catch (PackageManager.NameNotFoundException e) {
            updates.setText("Check for updates");
        }
        updates.setOnClickListener(x -> startActivity(new Intent(Intent.ACTION_VIEW, RELEASES)));

        TextInputEditText input = findViewById(R.id.input);
        View.OnClickListener check = x -> {
            String s = input.getText() == null ? "" : input.getText().toString().trim();
            if (s.isEmpty()) return;
            if (!s.contains("://")) s = "https://" + s;
            startActivity(new Intent(this, LinkActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(s))
                    .putExtra(LinkActivity.MANUAL, true));
        };
        findViewById(R.id.check).setOnClickListener(check);
        input.setOnEditorActionListener((v, id, e) -> {
            if (id != EditorInfo.IME_ACTION_GO) return false;
            check.onClick(v);
            return true;
        });

        ((TabLayout) findViewById(R.id.tabs)).addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                boolean history = tab.getPosition() == 1;
                findViewById(R.id.protectionPage).setVisibility(history ? View.GONE : View.VISIBLE);
                findViewById(R.id.historyPage).setVisibility(history ? View.VISIBLE : View.GONE);
                if (history) showHistory();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        MaterialButtonToggleGroup keep = findViewById(R.id.keep);
        keep.check(keep.findViewWithTag(String.valueOf(History.keepDays(this))).getId());
        keep.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            History.setKeepDays(this, Integer.parseInt((String) g.findViewById(id).getTag()));
            showHistory(); // shorter retention prunes immediately
        });
        ListView list = findViewById(R.id.historyList);
        list.setEmptyView(findViewById(R.id.historyEmpty));
        list.setOnItemClickListener((p, view, pos, id) -> entryMenu((Map<?, ?>) p.getItemAtPosition(pos)));
        findViewById(R.id.clearHistory).setOnClickListener(x -> new MaterialAlertDialogBuilder(this)
                .setTitle("Clear all history?")
                .setMessage("This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> { History.clear(this); showHistory(); })
                .show());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED) {
            ((MaterialSwitch) findViewById(R.id.statusNotification)).setChecked(false);
            Toast.makeText(this, "Notifications are blocked for LinkGuard.", Toast.LENGTH_LONG).show();
        }
    }

    private void entryMenu(Map<?, ?> entry) {
        String url = (String) entry.get("url");
        String clean = UrlChecker.check(url).cleaned; // null for non-web links: nothing safe to copy
        List<String> items = new ArrayList<>(List.of("Open (checked again)", "Delete"));
        if (clean != null) items.add(1, "Copy clean link");
        new MaterialAlertDialogBuilder(this)
                .setTitle(url)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    switch (items.get(w)) {
                        case "Open (checked again)":
                            startActivity(new Intent(this, LinkActivity.class).setAction(Intent.ACTION_VIEW)
                                    .setData(Uri.parse(url)).putExtra(LinkActivity.MANUAL, true));
                            break;
                        case "Copy clean link":
                            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Link", clean));
                            Toast.makeText(this, "Clean link copied", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            History.delete(this, (long) entry.get("id"));
                            showHistory();
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // May have changed from the notification's Pause/Resume button.
        ((MaterialSwitch) findViewById(R.id.enabled)).setChecked(enabled(this));
        showStatus(isDefault(), enabled(this));
        StatusNotification.update(this);
        showHistory();

        browsers.removeAllViews();
        String chosen = chosenBrowser(this);
        for (Map.Entry<String, String> b : browserList(this).entrySet()) {
            MaterialRadioButton r = new MaterialRadioButton(this);
            r.setId(View.generateViewId());
            r.setText(b.getValue());
            r.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            browsers.addView(r);
            if (b.getKey().equals(chosen)) r.setChecked(true);
            r.setOnCheckedChangeListener((x, checked) -> {
                if (checked) prefs(this).edit().putString("browser", b.getKey()).apply();
            });
        }
    }

    private void showHistory() {
        List<Map<String, Object>> rows = History.list(this);
        for (Map<String, Object> r : rows)
            r.put("info", DateUtils.formatDateTime(this, (long) r.get("t"),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH)
                    + " · " + r.get("result"));
        ((ListView) findViewById(R.id.historyList)).setAdapter(new SimpleAdapter(this, rows,
                android.R.layout.simple_list_item_2, new String[]{"url", "info"},
                new int[]{android.R.id.text1, android.R.id.text2}));
    }

    private boolean isDefault() {
        ResolveInfo def = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_VIEW, PROBE).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY);
        return def != null && getPackageName().equals(def.activityInfo.packageName);
    }

    private void showStatus(boolean isDefault, boolean enabled) {
        boolean on = isDefault && enabled;
        MaterialCardView card = findViewById(R.id.statusCard);
        ImageView icon = findViewById(R.id.statusIcon);
        TextView title = findViewById(R.id.statusTitle);
        TextView text = findViewById(R.id.statusText);
        int bg = MaterialColors.getColor(card, on ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorErrorContainer);
        int fg = MaterialColors.getColor(card, on ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer);
        card.setCardBackgroundColor(bg);
        icon.setImageResource(on ? R.drawable.ic_shield : R.drawable.ic_warning);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        title.setTextColor(fg);
        text.setTextColor(fg);
        title.setText(on ? "Protection on" : isDefault ? "Protection paused" : "Protection off");
        text.setText(on ? "Every link you open is checked and cleaned first."
                : isDefault ? "Links go straight to your browser, unchecked."
                : "LinkGuard isn't your default browser, so links skip it.");
        ((MaterialSwitch) findViewById(R.id.enabled)).setTextColor(fg);
        ((MaterialSwitch) findViewById(R.id.statusNotification)).setTextColor(fg);
        findViewById(R.id.setDefault).setVisibility(isDefault ? View.GONE : View.VISIBLE);
    }

    private void requestDefault() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 1);
                return;
            }
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        // Android silently refuses repeat role requests; fall back to the settings page.
        if (code == 1 && result != RESULT_OK) startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
    }

    /** Installed browsers other than LinkGuard: package -> name. */
    static Map<String, String> browserList(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (ResolveInfo r : pm.queryIntentActivities(
                new Intent(Intent.ACTION_VIEW, PROBE).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_ALL)) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(c.getPackageName())) out.put(pkg, r.loadLabel(pm).toString());
        }
        return out;
    }

    /** The saved browser if still installed, else the first one found. */
    static String chosenBrowser(Context c) {
        Map<String, String> all = browserList(c);
        String saved = prefs(c).getString("browser", null);
        if (saved != null && all.containsKey(saved)) return saved;
        return all.isEmpty() ? null : all.keySet().iterator().next();
    }

    /** False when the user paused checking: links pass through untouched. */
    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("linkguard", MODE_PRIVATE);
    }
}
