package com.prism.webshell;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import java.io.InputStream;
import java.util.Properties;

/**
 * A website, as an app.
 *
 * Everything site-specific comes from {@code assets/webapp.properties}, which Prism writes into each
 * copy of this APK: {@code url} is the page to open and {@code title} is the name. Nothing is derived
 * from the package name, because Prism gives every web app a package of its own.
 *
 * Links on the same site stay in the app; a link to another site opens in the phone's browser, the
 * way an installed web app behaves, so the app never turns into a general-purpose browser.
 */
public class MainActivity extends Activity {

    private WebView web;
    private String home = "about:blank";
    private String homeHost = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Properties config = new Properties();
        try (InputStream in = getAssets().open("webapp.properties")) {
            config.load(in);
        } catch (Exception ignored) {
            // A template that was never filled in opens a blank page rather than crashing.
        }
        home = config.getProperty("url", "about:blank");
        homeHost = hostOf(home);
        String title = config.getProperty("title", "");
        if (!title.isEmpty()) {
            setTitle(title);
            if (Build.VERSION.SDK_INT >= 28) {
                setTaskDescription(new ActivityManager.TaskDescription.Builder().setLabel(title).build());
            } else {
                setTaskDescription(legacyDescription(title));
            }
        }

        final ProgressBar progress = findViewById(R.id.progress);
        web = findViewById(R.id.web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme() == null ? "" : target.getScheme();
                boolean web = scheme.equals("http") || scheme.equals("https");
                if (web && sameSite(target.getHost())) return false;
                openOutside(target);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        // Downloads go to the system, which has a download manager and somewhere to put the file.
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                openOutside(Uri.parse(url)));

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) return;
        web.loadUrl(home);
    }

    @SuppressWarnings("deprecation")
    private static ActivityManager.TaskDescription legacyDescription(String title) {
        return new ActivityManager.TaskDescription(title);
    }

    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /** The same site, counting its subdomains and the bare domain ("m.example.com" is example.com). */
    private boolean sameSite(String host) {
        if (host == null || homeHost.isEmpty()) return true;
        String a = host.toLowerCase();
        String b = homeHost;
        if (a.startsWith("www.")) a = a.substring(4);
        if (b.startsWith("www.")) b = b.substring(4);
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private void openOutside(Uri target) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, target));
        } catch (ActivityNotFoundException ignored) {
            // Nothing on the phone handles it; staying on the page is the only sensible answer.
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }
}
