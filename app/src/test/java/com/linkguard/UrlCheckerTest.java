package com.linkguard;

import org.junit.Test;

public class UrlCheckerTest {

    private static void ok(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static boolean bad(String url) { return !UrlChecker.check(url).warnings.isEmpty(); }

    @Test
    public void checks() {
        // Homographs
        UrlChecker.Verdict v = UrlChecker.check("https://аmazon.com/dp/B0?utm_source=x&th=1");
        ok(!v.warnings.isEmpty(), "Cyrillic а flagged");
        ok("amazon.com".equals(v.fixedHost), "fix to amazon.com: " + v.fixedHost);
        ok("https://amazon.com/dp/B0?th=1".equals(v.fixed), "fixed url: " + v.fixed);
        ok(bad("https://xn--80ak6aa92e.com/"), "all-Cyrillic apple (punycode) flagged");
        ok(bad("https://amazön.com"), "accented amazon flagged");
        ok(!bad("https://müller.de"), "German domain not flagged");
        ok(!bad("https://例え.jp/"), "Japanese domain not flagged");
        ok(!bad("https://сосо.рф"), "Cyrillic name on Cyrillic TLD not flagged");

        // Lookalikes and tricks
        ok(bad("https://amaz0n.com"), "amaz0n");
        ok(bad("https://amazom.com"), "amazom");
        ok(bad("https://gogole.com"), "gogole");
        ok(bad("https://m1crosoft.com"), "m1crosoft");
        ok(bad("https://paypal.com.secure-login.xyz/"), "brand subdomain trick");
        ok(bad("https://accounts.google.com@evil.example/x"), "@ trick");
        ok(bad("http://192.168.1.1/"), "IP");
        ok(bad("http://0x7f000001/"), "hex IP");
        ok(bad("intent://scan#Intent;scheme=zxing;end"), "intent scheme");
        ok(UrlChecker.check("intent://scan#Intent;end").cleaned == null, "intent never forwarded");
        ok(bad("not a url"), "garbage");
        for (String good : new String[]{"https://google.com", "https://www.amazon.ae/", "https://ample.com",
                "https://aws.amazon.com", "https://emiratesnbd.com", "https://maps.google.co.uk/x",
                "https://microsoft.sharepoint.com/sites/a", "https://github.com/a/b"})
            ok(!bad(good), "false positive: " + good + " " + UrlChecker.check(good).warnings);

        // Tracker removal
        v = UrlChecker.check("https://www.amazon.ae/Mug/dp/B0/ref=sr_1_1?crid=ABC&keywords=mug&qid=1&th=1");
        ok("https://www.amazon.ae/Mug/dp/B0?keywords=mug&th=1".equals(v.cleaned), "amazon: " + v.cleaned);
        ok(v.removed.contains("crid") && v.removed.contains("ref"), "amazon removed list");
        v = UrlChecker.check("https://youtu.be/abc?si=XYZ&t=10");
        ok("https://youtu.be/abc?t=10".equals(v.cleaned), "youtube: " + v.cleaned);
        v = UrlChecker.check("https://example.com/a?id=5#top");
        ok("https://example.com/a?id=5#top".equals(v.cleaned) && v.removed.isEmpty(), "untouched link");

        // Redirect wrappers
        v = UrlChecker.check("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%3Ffbclid%3D1&sa=D");
        ok("https://example.com/".equals(v.cleaned), "google unwrap: " + v.cleaned);
        ok(bad("https://l.facebook.com/l.php?u=https%3A%2F%2Fpaypa1.com%2Flogin"), "wrapped lookalike flagged");

        ok(UrlChecker.check("https://bit.ly/abc").shortened, "shortener noted");
    }
}
