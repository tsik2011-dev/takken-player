package io.github.tsik2011dev.takken;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Shows the web player in a WebView; audio itself is played by the native Player. */
public class MainActivity extends Activity {
    private WebView wv;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Player.get(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xFF11161B : 0xFFF1F3F0;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        int f = decor.getSystemUiVisibility();
        if (!dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(f);

        wv = new WebView(this);
        wv.setBackgroundColor(bg);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        wv.addJavascriptInterface(new Bridge(), "TakkenNative");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(Player.SITE)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
        });
        if (state != null) wv.restoreState(state);
        if (state == null || wv.getUrl() == null) wv.loadUrl(Player.SITE + "?app=android");

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (wv != null) wv.saveState(out);
    }

    @Override public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else moveTaskToBack(true);
    }

    @Override protected void onResume() { super.onResume(); if (wv != null) wv.onResume(); }
    @Override protected void onPause() { if (wv != null) wv.onPause(); super.onPause(); }

    @Override protected void onDestroy() {
        if (wv != null) { wv.removeJavascriptInterface("TakkenNative"); wv.destroy(); wv = null; }
        super.onDestroy();
    }

    /** window.TakkenNative — called by the web player. */
    final class Bridge {
        private Player p() { return Player.get(MainActivity.this); }
        @JavascriptInterface public int version() { return 1; }
        @JavascriptInterface public String getState() { return p().stateJson(); }
        @JavascriptInterface public void configure(String json) { p().configure(json); }
        @JavascriptInterface public void unlock(String pass) { p().unlock(pass); }
        @JavascriptInterface public void setQueue(String json, boolean repeat) { p().setQueue(json, repeat); }
        @JavascriptInterface public void play(int index, double pos) { p().play(index, pos); }
        @JavascriptInterface public void toggle() { p().toggle(); }
        @JavascriptInterface public void resume() { p().resume(); }
        @JavascriptInterface public void pause() { p().pause(); }
        @JavascriptInterface public void next() { p().next(); }
        @JavascriptInterface public void prev() { p().prev(); }
        @JavascriptInterface public void seek(double sec) { p().seek(sec); }
        @JavascriptInterface public void setSpeed(double speed) { p().setSpeed((float) speed); }
        @JavascriptInterface public void setSleep(int minutes) { p().setSleep(minutes); }
        @JavascriptInterface public void download() { p().download(); }
        @JavascriptInterface public void forget() { p().forget(); }
        @JavascriptInterface public void openUrl(String url) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
        }
    }
}
