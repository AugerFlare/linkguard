# LinkGuard

An offline link checker for Android. LinkGuard sits between you and your browser: every link you tap is checked for phishing tricks and stripped of trackers, then opened in the browser you actually use.

**No internet permission.** LinkGuard can't send your links anywhere, and everything it does happens on your phone.

## What it does

**Warns about suspicious links** before they open:
- Lookalike letters, e.g. `аpple.com` spelled with a Cyrillic "а"
- Brand imitation, e.g. `paypa1.com`, `gooogle.com`, `paypal.com.secure-login.xyz`
- Decoy addresses like `https://google.com@evil.site`
- Raw IP addresses instead of website names
- Non-web links that could launch an app or file

For lookalike-letter links it offers to open the real site instead.

**Cleans links** by removing tracking parameters (`utm_*`, `fbclid`, `gclid`, Amazon `ref` tags, YouTube `si`, and more) and unwrapping redirect links from Google, Facebook, YouTube, Steam and Reddit.

**Also includes:**
- A switch to pause checking without changing your default browser
- Link history (kept 1, 7 or 30 days, or forever), stored only on your phone
- An optional status notification with a Pause/Resume button
- "Share" a link to LinkGuard from apps that open links internally
- A "Check for updates" button that opens this page's latest release (LinkGuard never goes online itself)

## Install

1. Download the latest APK from [Releases](https://github.com/AugerFlare/linkguard/releases/latest) on your phone and open it. Allow installs from unknown sources if asked.
2. Open LinkGuard and tap **Set as default browser**.
3. Pick which browser checked links should open in.

Requires Android 8.0 or newer.

## Build

Needs the Android SDK and JDK 17 or newer (Android Studio includes both).

```
gradlew assembleDebug testDebugUnitTest
```

On Windows, `build.bat` does the same using Android Studio's bundled JDK and writes the output to `build.log`. The APK lands in `app/build/outputs/apk/debug/`.

For a signed release build, create a keystore and a `keystore.properties` file in the project root (it's git-ignored):

```
storeFile=C:/path/to/linkguard-release.jks
keyAlias=linkguard
storePassword=...
keyPassword=...
```

then run `gradlew assembleRelease`. The APK lands in `app/build/outputs/apk/release/`.

## Limitations

- Shortened links (bit.ly, t.co, ...) are flagged, but their real destination can't be revealed without going online.
- Detection is heuristic. It catches common tricks, not every phishing site, and may occasionally flag a legitimate one.
- Domain parsing handles `name.com` and `name.co.uk` style addresses, not every country's rules.

## How it works

The checking logic lives in [`UrlChecker.java`](app/src/main/java/com/linkguard/UrlChecker.java), plain Java with no Android dependencies, covered by unit tests in [`UrlCheckerTest.java`](app/src/test/java/com/linkguard/UrlCheckerTest.java).
