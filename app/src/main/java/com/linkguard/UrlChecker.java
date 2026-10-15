package com.linkguard;

import java.net.IDN;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline link analysis. Plain Java, no Android or network dependencies. */
public final class UrlChecker {

    public static final class Verdict {
        public String cleaned;      // same destination, trackers removed; null if it must not be opened
        public String host = "";    // real host in ASCII/punycode
        public String shownAs = ""; // host as it renders on screen (Unicode)
        public String fixed;        // Latin-only version of a lookalike link, or null
        public String fixedHost;
        public boolean shortened;
        public final List<String> removed = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private static final Pattern URL = Pattern.compile(
            "^([a-zA-Z][a-zA-Z0-9+.\\-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$");

    private static final List<String> BRANDS = Arrays.asList(
            "amazon", "google", "apple", "microsoft", "paypal", "facebook", "instagram", "whatsapp",
            "netflix", "linkedin", "twitter", "outlook", "icloud", "yahoo", "github", "binance",
            "coinbase", "dropbox", "adobe", "ebay", "noon", "careem", "talabat", "emiratesnbd",
            "adcb", "bankfab", "mashreq", "etisalat", "emirates", "aramex", "dewa", "telegram",
            "tiktok", "snapchat", "steam", "roblox", "spotify");

    private static final Set<String> TLDS = set("com", "net", "org", "co", "io", "ae", "uk", "gov");
    private static final Set<String> SLD = set("co", "com", "org", "net", "gov", "ac", "edu", "ne", "or");

    private static final Set<String> SHORTENERS = set("bit.ly", "tinyurl.com", "t.co", "goo.gl",
            "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "t.ly",
            "rebrand.ly", "s.id", "lnkd.in", "bl.ink");

    // Tracking parameters. Entries ending in '*' are prefixes.
    private static final Set<String> TRACKERS = set("utm_*", "pk_*", "mtm_*", "hsa_*", "fbclid",
            "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
            "igshid", "igsh", "mibextid", "srsltid", "mc_cid", "mc_eid", "_ga", "_gl", "li_fat_id",
            "mkt_tok", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking",
            "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "rb_clickid", "s_cid", "wickedid");

    // Site-specific trackers, keyed by the registrable label (amazon.ae -> "amazon").
    private static final Map<String, Set<String>> SITE = new HashMap<>();
    static {
        SITE.put("amazon", set("ref", "ref_", "crid", "sprefix", "qid", "sr", "content-id",
                "_encoding", "pd_rd_*", "pf_rd_*", "linkcode", "linkid", "ascsubtag"));
        SITE.put("youtube", set("si", "feature", "pp"));
        SITE.put("youtu", set("si", "feature"));
        SITE.put("spotify", set("si"));
        SITE.put("twitter", set("s", "t", "ref_src", "ref_url"));
        SITE.put("x", set("s", "t", "ref_src", "ref_url"));
        SITE.put("tiktok", set("is_from_webapp", "sender_device", "sender_web_id", "_r", "_t"));
        SITE.put("linkedin", set("trk", "trackingid", "lipi", "midtoken", "midsig", "trkemail"));
        SITE.put("reddit", set("share_id"));
        SITE.put("aliexpress", set("spm", "scm", "pvid", "algo_pvid", "algo_exp_id", "aff_platform", "aff_trace_key"));
        SITE.put("ebay", set("_trkparms", "_trksid", "mkevt", "mkcid", "mkrid", "campid", "toolid", "amdata"));
    }

    // Non-Latin letters that render like Latin ones (Cyrillic, Greek, Armenian, odd Latin).
    private static final Map<Character, Character> CONFUSABLE = new HashMap<>();
    static {
        for (String p : ("аa еe оo рp сc уy хx іi јj ѕs һh ԁd ԛq ԝw ӏl кk үy ѵv "
                + "αa οo ρp νv ιi κk χx υu εe ɡg ɩi ıi ɑa օo սu ոn հh").split(" "))
            CONFUSABLE.put(p.charAt(0), p.charAt(1));
    }

    private static final EnumSet<Character.UnicodeScript> LATIN_LIKE = EnumSet.of(
            Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
            Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.CHEROKEE);

    public static Verdict check(String input) {
        Verdict v = new Verdict();
        String url = input.trim();
        for (int i = 0; i < 3; i++) {
            String inner = unwrap(url);
            if (inner == null) break;
            v.removed.add("redirect wrapper");
            url = inner;
        }

        Matcher m = URL.matcher(url);
        if (!m.matches()) { v.warnings.add("This isn't a readable web address."); return v; }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String authority = m.group(2), path = m.group(3);
        String query = m.group(4) == null ? "" : m.group(4).substring(1);
        String frag = m.group(5) == null ? "" : m.group(5);

        int at = authority.lastIndexOf('@');
        String userinfo = at >= 0 ? authority.substring(0, at) : "";
        String hostPort = authority.substring(at + 1);
        String host, port;
        if (hostPort.startsWith("[")) {
            int e = hostPort.indexOf(']');
            host = e > 0 ? hostPort.substring(0, e + 1) : hostPort;
        } else {
            int c = hostPort.indexOf(':');
            host = c >= 0 ? hostPort.substring(0, c) : hostPort;
        }
        port = hostPort.substring(host.length());
        host = decode(host.replace("+", "%2B")).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) { v.warnings.add("This link has no website address."); return v; }

        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            v.warnings.add("The website name is malformed.");
            ascii = host;
        }
        String uni = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED);
        v.host = ascii;
        v.shownAs = uni;

        if (!scheme.equals("http") && !scheme.equals("https")) {
            v.warnings.add("This isn't a normal web link (" + scheme + "://). It could launch an app or file.");
            return v; // cleaned stays null: never forward it
        }
        if (!userinfo.isEmpty())
            v.warnings.add("The text before “@” is a decoy. This link actually goes to " + ascii + ".");
        if (host.startsWith("[") || ascii.matches("(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*"))
            v.warnings.add("This goes to a raw IP address instead of a named website.");

        // Homographs: mixed alphabets, or a whole name built from Latin lookalikes.
        String[] uLabels = uni.split("\\.");
        boolean asciiTld = isAscii(uLabels[uLabels.length - 1]);
        boolean lookalike = false;
        for (String label : uLabels) {
            EnumSet<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
            label.codePoints().forEach(cp -> {
                Character.UnicodeScript s = Character.UnicodeScript.of(cp);
                if (LATIN_LIKE.contains(s)) scripts.add(s);
            });
            if (scripts.size() > 1) {
                v.warnings.add("It mixes " + names(scripts) + " letters so the name looks familiar. This is a common phishing trick.");
                lookalike = true;
            } else if (!isAscii(label) && isAscii(latin(label, false)) && asciiTld) {
                v.warnings.add("It's spelled with non-Latin letters that look like Latin ones.");
                lookalike = true;
            }
        }
        String skeleton = latin(uni, true);
        if (lookalike && isAscii(skeleton)) v.fixedHost = skeleton;

        // Brand imitation: typosquats (amaz0n, gogle) and brand-as-subdomain (paypal.com.evil.xyz).
        String[] labels = skeleton.split("\\.");
        String[] aLabels = ascii.split("\\.");
        int reg = registrableStart(labels);
        String regDomain = String.join(".", Arrays.copyOfRange(aLabels, reg, aLabels.length));
        if (!lookalike) {
            String brand = imitatedBrand(labels[reg]);
            if (brand != null && !aLabels[reg].equals(brand))
                v.warnings.add("The name imitates " + cap(brand) + ", but the site is " + regDomain + ".");
        }
        for (int i = 0; i + 1 < reg; i++) {
            if (BRANDS.contains(labels[i]) && TLDS.contains(labels[i + 1]) && !labels[reg].equals(labels[i])) {
                v.warnings.add("It starts with “" + labels[i] + "." + labels[i + 1] + "” but actually goes to " + regDomain + ".");
                break;
            }
        }

        String bare = ascii.startsWith("www.") ? ascii.substring(4) : ascii;
        v.shortened = SHORTENERS.contains(bare);

        // Strip trackers.
        String site = aLabels[reg];
        Set<String> siteRules = SITE.getOrDefault(site, Collections.emptySet());
        List<String> keep = new ArrayList<>();
        for (String p : query.split("&")) {
            if (p.isEmpty()) continue;
            String name = decode(p.split("=", 2)[0]).toLowerCase(Locale.ROOT);
            if (matches(TRACKERS, name) || matches(siteRules, name)) v.removed.add(name);
            else keep.add(p);
        }
        if (site.equals("amazon")) {
            String p = path.replaceAll("/ref=[^/]*", "");
            if (!p.equals(path)) { v.removed.add("ref"); path = p; }
        }
        String tail = path + (keep.isEmpty() ? "" : "?" + String.join("&", keep)) + frag;
        v.cleaned = scheme + "://" + authority + tail;
        if (v.fixedHost != null) v.fixed = scheme + "://" + v.fixedHost + port + tail;

        LinkedHashSet<String> dedup = new LinkedHashSet<>(v.removed);
        v.removed.clear();
        v.removed.addAll(dedup);
        return v;
    }

    /** Returns the destination hidden inside a known redirect wrapper, or null. */
    private static String unwrap(String url) {
        Matcher m = URL.matcher(url);
        if (!m.matches() || m.group(4) == null) return null;
        String host = m.group(2).toLowerCase(Locale.ROOT);
        host = host.substring(host.lastIndexOf('@') + 1);
        String path = m.group(3);
        List<String> keys;
        if (host.matches("(www\\.)?google(\\.[a-z]{2,3}){1,2}") && path.equals("/url")) keys = Arrays.asList("q", "url");
        else if (host.matches("(www\\.|m\\.)?youtube\\.com") && path.equals("/redirect")) keys = Arrays.asList("q");
        else if (host.matches("l\\.facebook\\.com|lm\\.facebook\\.com|l\\.messenger\\.com|l\\.instagram\\.com")) keys = Arrays.asList("u");
        else if (host.equals("steamcommunity.com") && path.startsWith("/linkfilter")) keys = Arrays.asList("url", "u");
        else if (host.equals("out.reddit.com")) keys = Arrays.asList("url");
        else return null;
        for (String p : m.group(4).substring(1).split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && keys.contains(kv[0])) {
                String target = decode(kv[1]);
                if (target.matches("(?is)https?://.+")) return target;
            }
        }
        return null;
    }

    private static String imitatedBrand(String label) {
        for (String one : new String[]{"l", "i"}) {
            String n = label.replace("0", "o").replace("1", one).replace("3", "e").replace("5", "s")
                    .replace("rn", "m").replace("vv", "w").replace("-", "");
            for (String b : BRANDS)
                if (n.equals(b) || (b.length() >= 6 && distance(n, b) == 1)) return b;
        }
        return null;
    }

    // ponytail: no Public Suffix List, handles name.tld and name.co.uk style; rare suffixes misjudged.
    private static int registrableStart(String[] labels) {
        int n = labels.length;
        if (n <= 2) return 0;
        return labels[n - 1].length() == 2 && SLD.contains(labels[n - 2]) ? n - 3 : n - 2;
    }

    private static boolean matches(Set<String> rules, String name) {
        if (rules.contains(name)) return true;
        for (String r : rules)
            if (r.endsWith("*") && name.startsWith(r.substring(0, r.length() - 1))) return true;
        return false;
    }

    /** Maps lookalike letters to Latin; optionally drops accents (ö -> o). */
    private static String latin(String s, boolean stripMarks) {
        if (stripMarks) s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(CONFUSABLE.getOrDefault(c, c));
        return b.toString();
    }

    /** Edit distance counting adjacent swaps as one edit (gogole -> google). */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        return d[a.length()][b.length()];
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static boolean isAscii(String s) { return s.chars().allMatch(c -> c < 128); }

    private static String names(Set<Character.UnicodeScript> scripts) {
        List<String> out = new ArrayList<>();
        for (Character.UnicodeScript s : scripts) out.add(cap(s.name().toLowerCase(Locale.ROOT)));
        return String.join(" and ", out);
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
}
