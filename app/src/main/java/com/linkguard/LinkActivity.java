package com.linkguard;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Invisible screen that receives every link, checks it, then hands it to the real browser. */
public class LinkActivity extends AppCompatActivity {

    static final String MANUAL = "manual";
    private static final Pattern LINK = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = extractUrl(getIntent());
        if (url == null) { finish(); return; }
        StatusNotification.update(this); // brings it back if the user swiped it away
        // Paused: pass the link through untouched. The in-app "Check" button still checks.
        if (!MainActivity.enabled(this) && !getIntent().getBooleanExtra(MANUAL, false)) { open(url); return; }

        UrlChecker.Verdict v = UrlChecker.check(url);
        long entry = History.add(this, url, summary(v));
        if (!v.warnings.isEmpty()) { warn(v, entry); return; }
        String msg = v.removed.isEmpty() ? "LinkGuard: link checked"
                : "LinkGuard: removed " + v.removed.size() + " tracker(s)";
        if (v.shortened) msg += "\nShortened link: its real destination is hidden.";
        // App context: this activity finishes immediately, the toast must outlive it.
        Toast.makeText(getApplicationContext(), msg, v.shortened ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        open(v.cleaned);
    }

    private static String extractUrl(Intent i) {
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            Matcher m = LINK.matcher(text);
            return m.find() ? m.group() : null;
        }
        return i.getDataString();
    }

    private static String summary(UrlChecker.Verdict v) {
        if (!v.warnings.isEmpty()) return "⚠ Warned: " + String.join(" ", v.warnings);
        String s = v.removed.isEmpty() ? "✓ Checked, nothing to remove" : "✓ Cleaned, removed " + String.join(", ", v.removed);
        return v.shortened ? s + " · shortened link" : s;
    }

    private void open(String url) {
        String browser = MainActivity.chosenBrowser(this);
        if (browser == null) {
            Toast.makeText(this, "No browser installed for LinkGuard to hand links to.", Toast.LENGTH_LONG).show();
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(browser) // explicit target, so the link never loops back here
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Couldn't open the browser. Pick another one in LinkGuard.", Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    private void warn(UrlChecker.Verdict v, long entry) {
        StringBuilder msg = new StringBuilder();
        for (String w : v.warnings) msg.append("• ").append(w).append("\n\n");
        if (!v.host.isEmpty()) {
            msg.append("Real address: ").append(v.host);
            if (!v.shownAs.equals(v.host)) msg.append("\nShown as: ").append(v.shownAs);
        }

        MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setIcon(R.drawable.ic_warning)
                .setTitle("Suspicious link")
                .setMessage(msg.toString().trim())
                .setNegativeButton("Go back", (x, y) -> choose(entry, "went back", null))
                .setOnCancelListener(x -> choose(entry, "went back", null));
        if (v.cleaned != null) d.setNeutralButton("Open anyway", (x, y) -> choose(entry, "opened anyway", v.cleaned));
        if (v.fixed != null) d.setPositiveButton("Open " + v.fixedHost, (x, y) -> choose(entry, "opened " + v.fixedHost, v.fixed));
        d.show();
    }

    /** Records what the user did with a warned link, then opens it (or not). */
    private void choose(long entry, String what, String url) {
        History.note(this, entry, "You " + what + ".");
        if (url != null) open(url);
        else finish();
    }
}
