package com.suanran.ruyi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

/**
 * 网页分身浏览器：每个槽位一个独立进程（BrowserP1..P8），
 * 进程内用 WebView.setDataDirectorySuffix 隔离数据目录，
 * cookie / localStorage / IndexedDB 各身份互不串扰。
 */
public abstract class BrowserActivity extends Activity {

    protected abstract int slot();

    public static final String EXTRA_ID = "web_id";
    public static final String EXTRA_WIPE = "web_wipe";

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private WebIds.Entry entry;

    public static Class<? extends BrowserActivity> classForSlot(int slot) {
        switch (slot) {
            case 1: return BrowserP1.class;
            case 2: return BrowserP2.class;
            case 3: return BrowserP3.class;
            case 4: return BrowserP4.class;
            case 5: return BrowserP5.class;
            case 6: return BrowserP6.class;
            case 7: return BrowserP7.class;
            case 8: return BrowserP8.class;
            default: return BrowserP1.class;
        }
    }

    public static Intent launchIntent(Context ctx, WebIds.Entry e) {
        Intent intent = new Intent(ctx, classForSlot(e.slot));
        intent.putExtra(EXTRA_ID, e.id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在创建任何 WebView 之前调用；同进程重复进入时已设置过，会抛异常，忽略即可
        try {
            WebView.setDataDirectorySuffix("web" + slot());
        } catch (IllegalStateException ignored) {
        }
        super.onCreate(savedInstanceState);

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        entry = new WebIds(this).byId(id);
        boolean wipe = getIntent().getBooleanExtra(EXTRA_WIPE, false);

        if (wipe || (entry != null && entry.fresh)) {
            wipeData();
            if (entry != null && entry.fresh) {
                entry.fresh = false;
                new WebIds(this).add(entry);
            }
        }
        if (wipe) {
            Toast.makeText(this, "该身份的数据已清空", Toast.LENGTH_SHORT).show();
            finish();
            Process.killProcess(Process.myPid());
            return;
        }
        if (entry == null) {
            Toast.makeText(this, "这个身份已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_browser);
        setTitle(entry.name);

        web = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.webProgress);
        Button backBtn = findViewById(R.id.btnBack);
        Button fwdBtn = findViewById(R.id.btnFwd);
        Button reloadBtn = findViewById(R.id.btnReload);
        Button goBtn = findViewById(R.id.btnGo);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        String ua = WebIds.uaString(entry.ua);
        if (!ua.isEmpty()) settings.setUserAgentString(ua);
        if ("desktop".equals(entry.ua)) {
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        backBtn.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        fwdBtn.setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        reloadBtn.setOnClickListener(v -> web.reload());
        goBtn.setOnClickListener(v -> loadFromBar());
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { loadFromBar(); return true; }
            return false;
        });

        String home = entry.home == null || entry.home.isEmpty() ? "https://claude.ai" : entry.home;
        web.loadUrl(home);
    }

    private void wipeData() {
        try { WebStorage.getInstance().deleteAllData(); } catch (Exception ignored) {}
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception ignored) {}
    }

    private void loadFromBar() {
        String raw = urlBar.getText().toString().trim();
        if (raw.isEmpty()) return;
        String url;
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            url = raw;
        } else if (raw.contains(".") && !raw.contains(" ")) {
            url = "https://" + raw;
        } else {
            url = "https://www.bing.com/search?q=" + Uri.encode(raw);
        }
        web.loadUrl(url);
        urlBar.clearFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
