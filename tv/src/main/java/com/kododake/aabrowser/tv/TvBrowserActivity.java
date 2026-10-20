package com.kododake.aabrowser.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A single-WebView TV browser with no mobile/car runtime or background services. */
public final class TvBrowserActivity extends Activity {
    private static final int BG = 0xff102036, FG = 0xffeef5ff, ACCENT = 0xff80cbc4;
    private LinearLayout root, toolbar;
    private FrameLayout viewport;
    private TextView status;
    private ProgressBar progress;
    private WebView web;
    private View panel, video;
    private WebChromeClient.CustomViewCallback videoCallback;
    private PointerView pointerView;
    private Button pageButton;
    private SharedPreferences prefs;
    private final RemotePointer pointer = new RemotePointer();
    private boolean pointerMode, home = true, failed, centerDown;
    private String lastUrl = "https://m.youtube.com/";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tv_browser", MODE_PRIVATE);
        lastUrl = prefs.getString("last_url", lastUrl);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(6), dp(12), dp(6));
        setContentView(root);
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setFocusable(false);
        toolbar = new LinearLayout(this);
        barScroll.addView(toolbar);
        root.addView(barScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        addTool("Home", this::showHome);
        addTool("Search", this::search);
        addTool("Bookmarks", this::bookmarks);
        addTool("Back", this::goBack);
        addTool("Reload", this::reload);
        pageButton = addTool("Use pointer", this::enterPage);
        status = text("Arrows: move focus • OK: select", 14);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        viewport = new FrameLayout(this);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        pointerView = new PointerView();
        pointerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pointerView.setVisibility(View.GONE);
        createWebView();
        viewport.addView(pointerView, new FrameLayout.LayoutParams(-1, -1));
        showHome();
    }

    private void createWebView() {
        web = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setBackgroundColor(Color.WHITE);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setOffscreenPreRaster(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // Keep the installed WebView's actual identity. No page polling, playback overrides or ad scripts.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) return false;
                status.setText("This link needs another app. Press Back for browser controls.");
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (home || "about:blank".equals(url)) return;
                lastUrl = url;
                failed = false;
                progress.setVisibility(View.VISIBLE);
                updateStatus();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // YouTube changes pages through History API without a full page load.
                if (!home && url != null && !"about:blank".equals(url)) {
                    lastUrl = url;
                    prefs.edit().putString("last_url", url).apply();
                    updateStatus();
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.INVISIBLE);
                if (home) web.clearHistory();
                if (!home && !failed) {
                    prefs.edit().putString("last_url", url).apply();
                    updateStatus();
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || home) return;
                showError("Page could not load", error.getDescription() + " (code " + error.getErrorCode() + ")");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                exitVideo();
                viewport.removeView(view);
                view.destroy();
                createWebView();
                pointerView.bringToFront();
                showError("Web page stopped", detail.didCrash()
                    ? "The TV's WebView renderer crashed. Retry to reopen the page."
                    : "Android closed the web page, possibly to reclaim memory. Retry to reopen it.");
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                if (value == 100) progress.setVisibility(View.INVISIBLE);
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (video != null) { callback.onCustomViewHidden(); return; }
                video = view;
                videoCallback = callback;
                root.getChildAt(0).setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
                web.setVisibility(View.INVISIBLE);
                viewport.addView(view, new FrameLayout.LayoutParams(-1, -1));
                pointerView.bringToFront();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                enterPage();
            }
            @Override public void onHideCustomView() { exitVideo(); }
        });
        viewport.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private void exitVideo() {
        if (video == null) return;
        viewport.removeView(video);
        video = null;
        WebChromeClient.CustomViewCallback callback = videoCallback;
        videoCallback = null;
        root.getChildAt(0).setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        web.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (callback != null) callback.onCustomViewHidden();
        leavePage();
    }

    private void navigate(String input) {
        String url = BrowserAddress.resolve(input);
        if (url == null) return;
        exitVideo();
        removePanel();
        home = false;
        failed = false;
        lastUrl = url;
        web.setVisibility(View.VISIBLE);
        web.onResume();
        web.loadUrl(url);
        enterPage();
    }

    private void showHome() {
        exitVideo();
        home = true;
        leavePage();
        web.stopLoading();
        // Release the previous document/video when returning home; there are no hidden tabs.
        web.loadUrl("about:blank");
        web.clearHistory();
        web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        LinearLayout contents = newPanel();
        contents.addView(text("AA Browser TV Lite", 28));
        contents.addView(text("Arrows move focus • OK opens • On a web page, arrows move the pointer and OK clicks • Back returns to controls", 16));
        Button first = addPanelButton(contents, "YouTube", () -> navigate("https://m.youtube.com/"));
        addPanelButton(contents, "Google", () -> navigate("https://www.google.com/"));
        addPanelButton(contents, "Search or enter address", this::search);
        addPanelButton(contents, "Bookmarks", this::bookmarks);
        addPanelButton(contents, "Reopen last page", () -> navigate(lastUrl));
        addPanelButton(contents, "TV help / WebView version", this::help);
        status.setText("TV-only • One page at a time • No background tabs");
        first.requestFocus();
    }

    private void search() {
        leavePage();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(FG);
        input.setHint("Search or https://example.com");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        if (!home) { input.setText(lastUrl); input.selectAll(); }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Search or address")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Go", (d, which) -> navigate(input.getText().toString())).create();
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                dialog.dismiss(); navigate(input.getText().toString()); return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    private JSONArray saved() {
        try { return new JSONArray(prefs.getString("bookmarks", "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private void bookmarks() {
        leavePage();
        JSONArray items = saved();
        String[] labels = new String[items.length() + 1];
        labels[0] = home ? "Add a website" : "Save current page";
        for (int i = 0; i < items.length(); i++) labels[i + 1] = items.optJSONObject(i).optString("title");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(labels, (d, index) -> {
                if (index == 0) { if (home) addBookmarkAddress(); else saveBookmark(lastUrl); }
                else {
                    JSONObject bookmark = items.optJSONObject(index - 1);
                    new AlertDialog.Builder(this).setTitle(bookmark.optString("title"))
                        .setItems(new String[]{"Open", "Delete"}, (choice, which) -> {
                            if (which == 0) navigate(bookmark.optString("url"));
                            else { items.remove(index - 1); prefs.edit().putString("bookmarks", items.toString()).apply(); bookmarks(); }
                        }).setNegativeButton("Cancel", null).show();
                }
            }).setNegativeButton("Close", null).create();
        dialog.show();
    }

    private void addBookmarkAddress() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com");
        new AlertDialog.Builder(this).setTitle("Add bookmark").setView(input)
            .setPositiveButton("Save", (d, which) -> {
                String url = BrowserAddress.resolve(input.getText().toString());
                if (url != null) saveBookmark(url);
            }).setNegativeButton("Cancel", null).show();
    }

    private void saveBookmark(String url) {
        JSONArray items = saved();
        for (int i = 0; i < items.length(); i++) {
            if (url.equals(items.optJSONObject(i).optString("url"))) { status.setText("Already bookmarked"); return; }
        }
        try {
            JSONObject item = new JSONObject();
            item.put("url", url);
            String title = !home && web.getTitle() != null ? web.getTitle() : url;
            item.put("title", title);
            items.put(item);
            prefs.edit().putString("bookmarks", items.toString()).apply();
            status.setText("Bookmark saved");
        } catch (JSONException ignored) { status.setText("Could not save bookmark"); }
    }

    private void reload() {
        if (!home) navigate(lastUrl);
    }

    private void showError(String title, String detail) {
        failed = true;
        leavePage();
        web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        LinearLayout contents = newPanel();
        contents.addView(text(title, 24));
        contents.addView(text(detail + "\n" + lastUrl, 16));
        contents.addView(text("Check the TV's connection. If YouTube alone fails, try another site and check Android System WebView updates in the TV Play Store.", 16));
        Button retry = addPanelButton(contents, "Retry", () -> navigate(lastUrl));
        addPanelButton(contents, "Search / another website", this::search);
        addPanelButton(contents, "Home", this::showHome);
        status.setText("Page loading failed — Retry or open another website");
        retry.requestFocus();
    }

    private void help() {
        String version = WebView.getCurrentWebViewPackage() == null ? "unavailable"
            : WebView.getCurrentWebViewPackage().versionName;
        new AlertDialog.Builder(this).setTitle("Mi remote controls")
            .setMessage("Home / dialogs: arrows move focus; OK selects.\n\nWeb pages: arrows move the visible pointer; OK clicks. Hold an arrow to move faster. At the top/bottom edge, the page scrolls.\n\nBack: leave fullscreen, or return from pointer to toolbar. Back again goes to the previous page.\n\nUse pointer returns to the page. On-screen keyboard keeps normal arrow/OK controls.\n\nYouTube: choose a lower playback quality in the player if needed. No ad blocker or video-modification scripts run in this build.\n\nAndroid System WebView: " + version)
            .setPositiveButton("OK", null).show();
    }

    private void enterPage() {
        if (home || failed) return;
        pointerMode = true;
        centerDown = false;
        web.requestFocus();
        pointer.center(viewport.getWidth(), viewport.getHeight());
        pointerView.setVisibility(View.VISIBLE);
        pointerView.bringToFront();
        pointerView.invalidate();
        updateStatus();
    }

    private void leavePage() {
        pointerMode = false;
        centerDown = false;
        if (pointerView != null) pointerView.setVisibility(View.GONE);
        if (pageButton != null) pageButton.requestFocus();
        if (status != null) updateStatus();
    }

    private void updateStatus() {
        status.setText(pointerMode ? "Arrows: pointer • OK: click • Back: browser controls" : "Arrows: move focus • OK: select • " + (home ? "Home" : lastUrl));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        WindowInsets insets = root.getRootWindowInsets();
        boolean keyboard = insets != null && insets.isVisible(WindowInsets.Type.ime());
        if (!pointerMode || keyboard) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        int dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                float step = dp(event.getRepeatCount() > 5 ? 24 : 12);
                int scroll = pointer.move(dx, dy, step, viewport.getWidth(), viewport.getHeight());
                if (scroll != 0 && video == null) web.scrollBy(0, scroll * 2);
                pointerView.invalidate();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) centerDown = true;
            else if (event.getAction() == KeyEvent.ACTION_UP && centerDown) {
                centerDown = false;
                View target = video != null ? video : web;
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pointer.x, pointer.y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, pointer.x, pointer.y, 0);
                target.dispatchTouchEvent(down);
                target.dispatchTouchEvent(up);
                down.recycle(); up.recycle();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (video != null) { exitVideo(); return; }
        if (pointerMode) { leavePage(); return; }
        goBack();
    }

    private void goBack() {
        android.webkit.WebBackForwardList history = web.copyBackForwardList();
        int previous = history.getCurrentIndex() - 1;
        boolean hasPreviousPage = previous >= 0 && !"about:blank".equals(history.getItemAtIndex(previous).getUrl());
        if (!home && !failed && web.canGoBack() && hasPreviousPage) {
            removePanel(); web.goBack(); enterPage();
        } else if (!home) showHome();
        else new AlertDialog.Builder(this).setTitle("Exit AA Browser TV Lite?")
            .setPositiveButton("Exit", (d, which) -> finish()).setNegativeButton("Stay", null).show();
    }

    private LinearLayout newPanel() {
        removePanel();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout contents = new LinearLayout(this);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(dp(24), dp(12), dp(24), dp(12));
        contents.setBackgroundColor(BG);
        scroll.addView(contents);
        panel = scroll;
        viewport.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        return contents;
    }
    private void removePanel() {
        if (panel != null) { viewport.removeView(panel); panel = null; }
    }
    private TextView text(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value); label.setTextColor(FG); label.setTextSize(size);
        label.setPadding(dp(8), dp(6), dp(8), dp(6));
        return label;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(16); button.setTextColor(FG);
        button.setAllCaps(false); button.setFocusable(true); button.setMinHeight(dp(48));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(0xff305767, ACCENT));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(0xff305767, ACCENT));
        background.addState(new int[]{}, shape(0xff1c3049, 0xff496078));
        button.setBackground(background);
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill); result.setCornerRadius(dp(8)); result.setStroke(dp(2), stroke);
        return result;
    }
    private Button addTool(String label, Runnable action) {
        Button button = button(label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        toolbar.addView(button, params);
        return button;
    }
    private Button addPanelButton(LinearLayout parent, String label, Runnable action) {
        Button button = button(label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(dp(4), dp(5), dp(4), dp(5));
        parent.addView(button, params);
        return button;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onPause() {
        exitVideo();
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }
    @Override public void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
    }
    @Override public void onDestroy() {
        exitVideo();
        viewport.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    private final class PointerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PointerView() { super(TvBrowserActivity.this); setFocusable(false); setClickable(false); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4)); paint.setColor(Color.BLACK);
            canvas.drawCircle(pointer.x, pointer.y, dp(9), paint);
            paint.setStrokeWidth(dp(2)); paint.setColor(Color.WHITE);
            canvas.drawCircle(pointer.x, pointer.y, dp(9), paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(ACCENT);
            canvas.drawCircle(pointer.x, pointer.y, dp(3), paint);
        }
    }
}
