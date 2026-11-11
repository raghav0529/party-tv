package com.raghav.karaoke;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.raghav.karaoke.core.ApiServer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class MainActivity extends Activity {
    private static final int PORT = 8765;
    private static final String TV_URL = "http://127.0.0.1:" + PORT + "/tv";
    private static final String OFFLINE =
            "Not connected to a network. Connect the TV to Wi-Fi, then reopen this app.";
    private static final int BACKGROUND = 0xFF0C201B;
    private static final String KARAOKE = "karaoke";

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView status;
    private WebView web;
    private ExoPlayerControl player;
    private UsbLibrary library;
    private volatile ApiServer server;
    private volatile boolean destroyed;
    private long lastBackAt;
    /** Asking the server which app is open takes its lock, so keys are routed here, one at a time and in order. */
    private final ExecutorService keyRouter = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "remote-key-router");
            t.setDaemon(true);
            return t;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        status = new TextView(this);
        status.setText("Starting Party TV...");
        status.setTextColor(Color.rgb(0xD9, 0xB2, 0x6A));
        status.setTextSize(28);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        hideSystemBars();

        player = new ExoPlayerControl(this);
        library = new UsbLibrary(this);
        createWebView();
        startServer();

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    private void startServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String joinUrl = waitForJoinUrl();
                // The port may still be held briefly by an instance that is shutting down.
                for (int attempt = 0; attempt < 20 && !destroyed; attempt++) {
                    ApiServer candidate = new ApiServer(PORT, player, library, joinUrl);
                    player.setServer(candidate);
                    try {
                        candidate.start();
                        server = candidate;
                        if (destroyed) {
                            candidate.stop();
                        }
                        return;
                    } catch (IOException e) {
                        SystemClock.sleep(500);
                    }
                }
            }
        }, "server-start");
        t.setDaemon(true);
        t.start();
    }

    /** The network can come up a moment after the app on a TV that just woke, so wait a little before giving up. */
    private String waitForJoinUrl() {
        for (int attempt = 0; attempt < 10 && !destroyed; attempt++) {
            String ip = findLanIp();
            if (ip != null) {
                return "http://" + ip + ":" + PORT + "/";
            }
            SystemClock.sleep(500);
        }
        return OFFLINE;
    }

    private static String findLanIp() {
        String fallback = null;
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nic.getName();
                if (!nic.isUp() || nic.isLoopback() || name.startsWith("p2p")) {
                    continue; // p2p0 is Wi-Fi Direct and has an address phones cannot reach.
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return address.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Treated the same as having no network.
        }
        return fallback;
    }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(BACKGROUND);
        web.setVisibility(View.INVISIBLE);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            private boolean failed;

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                failed = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    failed = true;
                    retryLoad(view);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    failed = true;
                    retryLoad(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!failed) {
                    view.setVisibility(View.VISIBLE);
                    status.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Likely on 1 GB of RAM. Songs carry on; only the screen needs rebuilding.
                if (view == web) {
                    root.removeView(view);
                    view.destroy();
                    status.setVisibility(View.VISIBLE);
                    createWebView();
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(TV_URL);
    }

    private void retryLoad(final WebView view) {
        view.setVisibility(View.INVISIBLE);
        status.setVisibility(View.VISIBLE);
        main.removeCallbacksAndMessages(null);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && view == web) {
                    view.loadUrl(TV_URL);
                }
            }
        }, 1000);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        library.rescan();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    onRemoteKey(code);
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void onRemoteKey(final int code) {
        if (destroyed) {
            return;
        }
        final ApiServer s = server;
        if (s == null) {
            // Still starting: nothing is open yet, so Back can only mean leaving.
            if (code == KeyEvent.KEYCODE_BACK) {
                backOnHome();
            }
            return;
        }
        keyRouter.execute(new Runnable() {
            @Override
            public void run() {
                routeKey(s, code);
            }
        });
    }

    /** Runs on the key router, never on the main thread. */
    private void routeKey(final ApiServer s, int code) {
        String active = s.activeApp();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (active == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        backOnHome();
                    }
                });
            } else {
                s.goHome(); // Every screen follows through its own hub polling.
            }
            return;
        }
        if (KARAOKE.equals(active) && isPauseKey(code)) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
                    s.togglePause();
                }
            });
        } else if (KARAOKE.equals(active) && isSkipKey(code)) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
                    s.skip();
                }
            });
        } else {
            sendKeyToPage(pageKeyName(code));
        }
    }

    private static boolean isPauseKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    private static boolean isSkipKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    private static String pageKeyName(int code) {
        if (isSkipKey(code)) {
            return "right";
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "left";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "down";
            default:
                return "ok";
        }
    }

    /** name is always one of the fixed words from pageKeyName, so it is safe inside the script. */
    private void sendKeyToPage(String name) {
        final String script = "if(window.onRemoteKey){window.onRemoteKey('" + name + "');}";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && web != null) {
                    web.evaluateJavascript(script, null);
                }
            }
        });
    }

    private void backOnHome() {
        long now = SystemClock.elapsedRealtime();
        if (lastBackAt != 0 && now - lastBackAt < 2000) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Press Back again to exit Party TV", Toast.LENGTH_SHORT).show();
        }
    }

    private static void runOffMain(Runnable r) {
        Thread t = new Thread(r, "remote-key");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving with Home must not keep singing behind the TV's home screen. OK resumes on return.
        final ApiServer s = server;
        if (s != null && !isFinishing() && player.isPlaying()) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
                    s.togglePause();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        keyRouter.shutdown();
        final ApiServer s = server;
        if (s != null) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
                    s.stop();
                }
            });
        }
        player.release();
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
