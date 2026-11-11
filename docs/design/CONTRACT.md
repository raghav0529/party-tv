# Karaoke TV: shared contract

Standalone Android TV app. Target: Xiaomi Mi TV, Android TV 9 (API 28), 1 GB RAM, unknown WebView version. Everything runs on the TV. No laptop backend. Family sings together: no names, turns, scores, or login.

Do not touch the real TV. Do not run any `adb` command. Only edit files you own (below). Plain text only: no emojis or em dashes in UI strings or docs.

## Layout and ownership

| Path | Owner | Contents |
|---|---|---|
| `core/src/main/java/com/raghav/karaoke/core/` | Backend | Pure Java, NO android.* imports. Runs on JVM and Android. |
| `core/src/test/java/...` | Backend | JUnit tests |
| `core/src/main/resources/web/phone.html` | Phone frontend | Served at `/` |
| `core/src/main/resources/web/tv.html` | TV frontend | Served at `/tv`, shown in the TV WebView |
| `app/` | TV frontend | Android shell: Activity, ExoPlayer, audio processor, USB library |

Gradle: root `settings.gradle` includes `:core` and `:app`. `core/build.gradle` exists (nanohttpd, gson, zxing). Java 17. Run `./gradlew` from `projects/karaoke-tv`, with `JAVA_HOME` already set.

## Interfaces in core (Backend writes these exactly; TV frontend codes against them)

```java
package com.raghav.karaoke.core;

public final class Song {            // gson-serialised as is
  public String id;                  // saavn id, or "usb:<absolute path>"
  public String title, artist, album, image;   // image may be null
  public int durationSec;
  public String source;              // "saavn" or "usb"
  public transient String streamUrl; // https url or file path; NOT sent to phones
  public String uid;                 // unique per queue entry, set when queued
}

public interface PlayerControl {     // implemented by app (ExoPlayer) and Harness (fake clock)
  void play(Song song);              // start song immediately, replacing current. Thread-safe.
  void stop();
  void setPaused(boolean paused);
  void setVocalCut(boolean on);
  long positionMs();                 // thread-safe, must not block
  boolean isPlaying();
}

public interface LocalLibrary {      // implemented by app (USB scan); Harness uses an empty one
  java.util.List<Song> search(String query);
}

public final class ApiServer {       // extends or wraps NanoHTTPD
  public ApiServer(int port, PlayerControl player, LocalLibrary library, String joinUrl);
  public void start() throws java.io.IOException;
  public void stop();
  public void onSongEnded();                   // player calls this: advance to next
  public void onSongFailed(String message);    // player calls this: set lastError, advance to next
  public void togglePause();                   // used by remote OK button
  public void skip();                          // used by remote
}
```

`ApiServer` owns the queue, search, and lyric fetch. When the queue is empty and a song is added, it starts at once. When a song ends, the next starts automatically.

## HTTP API (port 8765, JSON, UTF-8)

| Route | Behaviour |
|---|---|
| `GET /` | phone.html |
| `GET /tv` | tv.html |
| `GET /qr.svg` | QR of joinUrl, black on white, with quiet zone |
| `GET /api/search?q=` | `{"results":[Song...]}`. USB matches first, then Saavn, max 15 |
| `POST /api/queue` body `{"id":"..."}` | Adds a song from recent search results. Returns `{"ok":true,"uid":"..."}`, or 404 with `{"ok":false,"error":"..."}` |
| `POST /api/queue/remove` body `{"uid":"..."}` | Removes an upcoming song |
| `POST /api/control` body `{"action":"..."}` | `toggle_pause`, `skip`, `toggle_vocals`. Returns state |
| `GET /api/state` | See below |
| `GET /api/lyrics?uid=` | `{"uid":"...","type":"synced"|"plain"|"none"|"loading","lines":[{"t":12340,"text":"..."}]}`. Plain lines have `t` of -1. |

`/api/state`:
```json
{"joinUrl":"http://192.0.2.10:8765/","now":{Song}|null,"positionMs":12345,"playing":true,
 "vocalCut":false,"queue":[Song...],"lastError":null}
```

Vocal cut defaults to OFF. `lastError` clears when the next song starts playing normally.

## Web page rules (TV WebView may be old, phones vary)

- ES5 JavaScript only: `var`, `function`, `XMLHttpRequest`. No arrow functions, `fetch`, `const`, modules, CSS grid-dependent layouts, or external CDNs or fonts. Single self-contained file.
- Must render Devanagari and Latin text. Use the system font stack.
- Poll `/api/state` (TV every 500 ms, phone every 2 s). Between polls TV interpolates position with the clock while playing.
- Show useful messages on network failures and never a blank screen.
- Diwali colours: deep maroon/near-black background, marigold and diya gold accents.

## Android facts

minSdk 26, compileSdk 34, targetSdk 28 (avoids scoped-storage changes), applicationId `com.raghav.karaoke`. Release build, debug-signed, no minify. `usesCleartextTraffic=true` (the WebView loads `http://127.0.0.1:8765/tv`). Leanback launcher activity with a banner. Media3 ExoPlayer. Server port 8765.
