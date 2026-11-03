package kr.chaeum.fieldremote;

import android.app.Presentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 화면 공유: 휴대폰을 TV에 화면 미러링(스마트 뷰·Miracast)이나 HDMI 케이블로 연결하면
 * TV 쪽에는 휴대폰 화면 대신 고른 이미지·영상·PDF만 TV 해상도에 맞춰 보여 줌 (안드로이드 Presentation).
 * 휴대폰에는 리모컨 화면이 그대로 남아 조작할 수 있음.
 */
final class ScreenShare implements DisplayManager.DisplayListener {
    private final MainActivity act;
    private final DisplayManager dm;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TvScreen screen;
    private int dispId = -1, dispW = 1920, dispH = 1080;

    // 지금 TV에 보여 줄 것
    private Uri uri;
    private String type = "";
    private int page = 0;
    private boolean fill = false, black = true, loop = true, muted = false;
    private int seq = 0;            // 늦게 끝난 이전 작업 무시용

    // 영상
    private MediaPlayer mp;
    private Surface surface;
    private boolean playing = false, resumeAfterBlack = false;
    private int vidW = 0, vidH = 0;

    // PDF (작업 스레드에서만 다룸)
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFd;
    private Uri pdfUri;

    private String error = "";

    // 확대·위치 (사진·영상·PDF). z = 배율(1 = 원래), x·y = 가운데에서 옮긴 거리 (TV 가로·세로 대비 비율)
    private float vz = 1f, vx = 0f, vy = 0f;
    private int pdfQ = 1;                       // PDF를 몇 배 해상도로 그렸는지 (확대하면 더 선명하게 다시 그림)
    private final Runnable pdfSharpen = this::render;

    // PPT: TV 화면의 WebView에서 pptx를 그림 (assets/pptx-render.js)
    private String pptJs;                 // 그리기 라이브러리 (처음 한 번 읽음)
    private volatile Uri webFile;         // https://pptx.local/file 로 넘겨줄 파일
    private Uri webPpt;                   // 지금 WebView에 올라가 있는 PPT
    private int slideCount = 0;
    private final TvJs tvJs = new TvJs(this);
    private final WebViewClient webClient = new WebViewClient() {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri url = req.getUrl();
            Uri f = webFile;
            if (f != null && "pptx.local".equals(url.getHost()) && "/file".equals(url.getPath())) {
                try { return new WebResourceResponse("application/octet-stream", null, act.getContentResolver().openInputStream(f)); }
                catch (Exception e) { return null; }
            }
            return null;
        }
    };

    ScreenShare(MainActivity a) {
        act = a;
        dm = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
    }

    void start() { dm.registerDisplayListener(this, Hub.main); refresh(); }

    void stop() {
        dm.unregisterDisplayListener(this);
        releaseVideo();
        dismiss();
        worker.execute(this::closePdf);
        worker.shutdown();
    }

    // ---------- TV 화면 찾기 ----------
    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }

    private Display findDisplay() {
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return ds.length > 0 ? ds[0] : null;
    }

    void refresh() {
        Display d = findDisplay();
        if (d == null) { releaseVideo(); dismiss(); emit(); return; }
        Display.Mode m = d.getMode();
        int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
        // TV 화면이 아직 떠 있을 때만 그대로 씀 (안드로이드가 스스로 닫았으면 새로 만듦)
        if (screen != null && d.getDisplayId() == dispId && screen.isShowing()) {
            if (w != dispW || h != dispH) { dispW = w; dispH = h; render(); }
            emit();
            return;
        }
        releaseVideo();
        dismiss();
        dispId = d.getDisplayId(); dispW = w; dispH = h;
        webPpt = null;
        TvScreen s = new TvScreen(act, d);
        // 화면 비율·밀도가 바뀌거나 앱을 잠깐 나갔다 오면 안드로이드가 TV 화면을 스스로 닫음 → 알아채고 다시 만듦
        s.setOnDismissListener(x -> {
            if (screen != x) return;   // 우리가 닫은 것
            screen = null; dispId = -1; webPpt = null;
            releaseVideo();
            Hub.main.postDelayed(this::refresh, 300);
        });
        try { s.show(); } catch (RuntimeException e) { dispId = -1; emit(); Hub.main.postDelayed(this::refresh, 1500); return; }
        screen = s;
        act.setShareKeepOn(true);
        render();
        emit();
    }

    private void dismiss() {
        TvScreen old = screen;
        screen = null; dispId = -1; webPpt = null;   // 먼저 비워서 닫힘 알림이 '스스로 닫힘'으로 오해되지 않게
        if (old != null) { try { old.dismiss(); } catch (Exception ignored) {} }
        act.setShareKeepOn(false);
    }

    /** TV에 나오는 화면: 검은 바탕에 이미지(또는 PDF 장)와 영상만 있음. 다른 UI는 없음 */
    static final class TvScreen extends Presentation {
        FrameLayout root;
        TextureView tex;
        ImageView img;
        View cover;
        WebView web;   // HTML 파일용 (처음 쓸 때 만듦)

        TvScreen(Context c, Display d) { super(c, d); }

        @android.annotation.SuppressLint("SetJavaScriptEnabled")
        WebView web() {
            if (web != null) return web;
            WebView v = new WebView(getContext());
            WebSettings ws = v.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setTextZoom(100);
            // 페이지 폭을 TV 해상도(가로 픽셀)에 맞추고 화면에 꽉 맞게 축소·확대 → CSS 1px = TV 1픽셀
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setSupportZoom(false);
            ws.setBuiltInZoomControls(false);
            v.setVerticalScrollBarEnabled(false);
            v.setHorizontalScrollBarEnabled(false);
            v.setWebViewClient(new WebViewClient());   // 링크를 눌러도 다른 앱(브라우저)이 뜨지 않게
            v.setBackgroundColor(Color.WHITE);
            v.setVisibility(View.GONE);
            root.addView(v, root.indexOfChild(cover), new FrameLayout.LayoutParams(-1, -1));
            web = v;
            return v;
        }

        void hideWeb() {
            if (web == null) return;
            web.loadUrl("about:blank");   // 소리·애니메이션 멈춤
            web.setVisibility(View.GONE);
        }

        @Override protected void onStop() {
            if (web != null) { try { web.destroy(); } catch (Exception ignored) {} web = null; }
            super.onStop();
        }

        @SuppressWarnings("deprecation")
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            Context c = getContext();
            root = new FrameLayout(c);
            root.setBackgroundColor(Color.BLACK);
            tex = new TextureView(c);
            img = new ImageView(c);
            img.setBackgroundColor(Color.BLACK);
            cover = new View(c);
            cover.setBackgroundColor(Color.BLACK);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            root.addView(tex, lp);
            root.addView(img, new FrameLayout.LayoutParams(-1, -1));
            root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            tex.setVisibility(View.GONE);
            img.setVisibility(View.GONE);
            setContentView(root);
            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ---------- 화면(JS)에서 부르는 기능 ----------
    void show(String json) {
        try {
            JSONObject j = new JSONObject(json);
            uri = Uri.parse(j.getString("uri"));
            type = j.optString("type", "image");
            page = Math.max(0, j.optInt("page", 0));
            setViewValues((float) j.optDouble("z", 1), (float) j.optDouble("x", 0), (float) j.optDouble("y", 0));
        } catch (Exception e) { return; }
        black = false;
        error = "";
        render();
        emit();
    }

    void setBlack(boolean on) {
        black = on;
        if (on && mp != null && playing) { resumeAfterBlack = true; pauseVideo(); }
        applyCover();
        if (!on && resumeAfterBlack && mp != null) { resumeAfterBlack = false; playVideo(); }
        emit();
    }

    void setFill(boolean on) {
        fill = on;
        if ("video".equals(type)) fitVideo();
        else if (uri != null && !"html".equals(type) && !"ppt".equals(type)) render();   // HTML·PPT는 항상 TV 화면 전체
        emit();
    }
    void setLoop(boolean on) { loop = on; if (mp != null) mp.setLooping(on); emit(); }
    void setMute(boolean on) { muted = on; if (mp != null) mp.setVolume(on ? 0 : 1, on ? 0 : 1); emit(); }

    void video(String cmd) {
        if (mp == null) return;
        switch (cmd) {
            case "play": playVideo(); break;
            case "pause": pauseVideo(); break;
            case "toggle": if (playing) pauseVideo(); else playVideo(); break;
            case "restart": mp.seekTo(0); playVideo(); break;
        }
        emit();
    }

    /** 확대·위치 바꾸기 (손가락으로 움직이는 동안 계속 불림) */
    void setView(float z, float x, float y) {
        setViewValues(z, x, y);
        applyView();
        // PDF는 많이 확대하면 더 높은 해상도로 다시 그림 (손을 뗀 뒤 잠깐 있다가)
        if ("pdf".equals(type) && pdfQuality() > pdfQ) { Hub.main.removeCallbacks(pdfSharpen); Hub.main.postDelayed(pdfSharpen, 350); }
    }

    private void setViewValues(float z, float x, float y) {
        if (Float.isNaN(z) || Float.isNaN(x) || Float.isNaN(y)) return;
        vz = Math.max(1f, Math.min(8f, z));
        float lim = vz / 2f + 0.5f;   // 화면 밖으로 완전히 사라지지 않을 만큼만
        vx = Math.max(-lim, Math.min(lim, x));
        vy = Math.max(-lim, Math.min(lim, y));
    }

    private int pdfQuality() { return vz <= 1.2f ? 1 : vz <= 2.2f ? 2 : 3; }

    // TV 화면의 사진·영상 뷰 자체를 키우고 옮김 (GPU로 처리돼 부드러움)
    private void applyView() {
        if (screen == null || screen.root == null) return;
        boolean on = "image".equals(type) || "video".equals(type) || "pdf".equals(type);
        float z = on ? vz : 1f;
        float w = screen.root.getWidth() > 0 ? screen.root.getWidth() : dispW;
        float h = screen.root.getHeight() > 0 ? screen.root.getHeight() : dispH;
        for (View v : new View[]{screen.img, screen.tex}) {
            v.setScaleX(z); v.setScaleY(z);
            v.setTranslationX(on ? vx * w : 0); v.setTranslationY(on ? vy * h : 0);
        }
    }

    void clear() { uri = null; type = ""; black = true; releaseVideo(); render(); emit(); }

    // ---------- 그리기 ----------
    private void applyCover() { if (screen != null && screen.cover != null) screen.cover.setVisibility(black ? View.VISIBLE : View.GONE); }

    private void render() {
        final int my = ++seq;
        applyCover();
        applyView();
        if (screen == null || uri == null) { releaseVideo(); if (screen != null) { screen.img.setVisibility(View.GONE); screen.tex.setVisibility(View.GONE); hideWeb(); } return; }
        final Uri u = uri;
        final int W = dispW, H = dispH, pg = page;
        final boolean fl = fill;
        final int q = pdfQ = pdfQuality();
        if (!"html".equals(type) && !"ppt".equals(type)) hideWeb();
        if ("video".equals(type)) { startVideo(u, my); return; }
        releaseVideo();
        screen.tex.setVisibility(View.GONE);
        final String t = type;
        if ("ppt".equals(t)) {
            // 같은 PPT면 다시 읽지 않고 장만 바꿈
            if (u.equals(webPpt) && screen.web != null) { screen.web.evaluateJavascript("showSlide(" + pg + ")", null); return; }
            worker.execute(() -> {
                try {
                    String js = pptJs();
                    Hub.main.post(() -> { if (my == seq && screen != null) showPpt(u, pg, W, H, js); });
                } catch (Throwable e) { fail("PPT를 열 수 없어요."); }
            });
            return;
        }
        worker.execute(() -> {
            try {
                if ("html".equals(t)) {
                    String html = withViewport(readText(u), W);
                    Hub.main.post(() -> { if (my == seq && screen != null) showHtml(html); });
                } else if ("pdf".equals(t)) {
                    Bitmap bm = renderPdf(u, pg, W, H, fl, q);
                    Hub.main.post(() -> { if (my == seq && screen != null) showImage(bm, null); });
                } else {
                    Drawable dr = decodeImage(u, W, H);
                    Hub.main.post(() -> { if (my == seq && screen != null) showImage(null, dr); });
                }
            } catch (Throwable e) {
                fail("파일을 열 수 없어요. 지워졌거나 옮겨졌을 수 있어요.");
            }
        });
    }

    private void hideWeb() { if (screen != null) screen.hideWeb(); webPpt = null; }

    // ---------- PPT ----------
    private String pptJs() throws Exception {
        if (pptJs != null) return pptJs;
        try (InputStream in = act.getAssets().open("pptx-render.js")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            pptJs = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
        return pptJs;
    }

    // 슬라이드를 TV 해상도(W×H) 그대로 그림. 슬라이드 비율이 다르면 가운데 두고 남는 곳은 검게
    private void showPpt(Uri u, int pg, int W, int H, String js) {
        screen.img.setVisibility(View.GONE);
        screen.img.setImageDrawable(null);
        webFile = u;
        slideCount = 0;
        WebView v = screen.web();
        v.setWebViewClient(webClient);
        v.addJavascriptInterface(tvJs, "TV");
        String run = "var W=" + W + ",H=" + H + ",S=null,P=null;"
            + "function load(){if(!P)P=fetch('https://pptx.local/file').then(function(r){if(!r.ok)throw new Error('파일 없음');return r.arrayBuffer()})"
            + ".then(function(b){return pptxToHtml(b,{width:W,height:H,scaleToFit:true,letterbox:true})})"
            + ".then(function(s){S=s;TV.slides(s.length);return s})"
            + ".catch(function(e){TV.error(String(e&&e.message||e));return null});return P}"
            + "function showSlide(i){load().then(function(s){if(!s||!s.length)return;i=Math.max(0,Math.min(i,s.length-1));document.getElementById('w').innerHTML=s[i]})}"
            + "showSlide(" + pg + ");";
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=" + W + ", user-scalable=no\">"
            + "<style>html,body{margin:0;width:" + W + "px;height:" + H + "px;overflow:hidden;background:#000;font-family:sans-serif}"
            + "#w{position:absolute;left:0;top:0;width:" + W + "px;height:" + H + "px;overflow:hidden}</style></head>"
            + "<body><div id=\"w\"></div><script>" + js + "</script><script>" + run + "</script></body></html>";
        v.loadDataWithBaseURL("https://pptx.local/", html, "text/html", "utf-8", null);
        v.setVisibility(View.VISIBLE);
        webPpt = u;
    }

    void onSlides(int n) { slideCount = n; emit(); }

    void onPptError(String msg) {
        webPpt = null;
        fail("PPT를 그리지 못했어요. PDF로 저장해서 올려 주세요.");
    }

    // ---------- HTML ----------
    private void showHtml(String html) {
        screen.img.setVisibility(View.GONE);
        screen.img.setImageDrawable(null);
        webPpt = null;
        WebView v = screen.web();
        v.setWebViewClient(webClient);
        v.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null);
        v.setVisibility(View.VISIBLE);
    }

    private String readText(Uri u) throws Exception {
        try (InputStream in = act.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("no stream");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 80L * 1024 * 1024) throw new Exception("too big");
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final Pattern VIEWPORT = Pattern.compile("<meta[^>]*name\\s*=\\s*[\"']?viewport[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);

    // 페이지가 정한 화면 폭 설정을 TV 가로 픽셀 수로 바꿈 (예: 1920 → 1920×1080 화면으로 그려짐)
    static String withViewport(String html, int w) {
        String meta = "<meta name=\"viewport\" content=\"width=" + w + ", user-scalable=no\">";
        String s = VIEWPORT.matcher(html).replaceAll("");
        java.util.regex.Matcher m = HEAD.matcher(s);
        if (m.find()) return s.substring(0, m.end()) + meta + s.substring(m.end());
        return meta + s;
    }

    private void showImage(Bitmap bm, Drawable dr) {
        ImageView v = screen.img;
        v.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (bm != null) v.setImageBitmap(bm); else v.setImageDrawable(dr);
        if (dr instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable a = (AnimatedImageDrawable) dr;
            a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            a.start();
        }
        v.setVisibility(View.VISIBLE);
        hideWeb();
    }

    private Drawable decodeImage(Uri u, int W, int H) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(act.getContentResolver(), u);
        // 확대해도 선명하게 원본 해상도로 읽되, 긴 쪽이 4096픽셀을 넘는 아주 큰 사진만 줄여서 읽음 (메모리 아끼기)
        // 화면에 맞추는 건 ImageView가 TV 해상도로 함
        return ImageDecoder.decodeDrawable(src, (dec, info, s) -> {
            Size sz = info.getSize();
            int sample = 1;
            while (Math.max(sz.getWidth(), sz.getHeight()) / sample > 4096) sample *= 2;
            dec.setTargetSampleSize(sample);
        });
    }

    // PDF 한 장을 TV 해상도에 딱 맞는 크기로 그림
    private Bitmap renderPdf(Uri u, int pg, int W, int H, boolean fl, int q) throws Exception {
        openPdf(u);
        int n = pdf.getPageCount();
        PdfRenderer.Page p = pdf.openPage(Math.min(pg, n - 1));
        try {
            float pw = p.getWidth(), ph = p.getHeight();
            float s = (fl ? Math.max(W / pw, H / ph) : Math.min(W / pw, H / ph)) * q;
            // 너무 큰 그림은 메모리가 모자라니 2400만 화소까지만
            float px = pw * s * ph * s;
            if (px > 24_000_000f) s *= (float) Math.sqrt(24_000_000f / px);
            int bw = Math.max(1, Math.min(8192, Math.round(pw * s)));
            int bh = Math.max(1, Math.min(8192, Math.round(ph * s)));
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bm;
        } finally { p.close(); }
    }

    private void openPdf(Uri u) throws Exception {
        if (pdf != null && u.equals(pdfUri)) return;
        closePdf();
        pdfFd = act.getContentResolver().openFileDescriptor(u, "r");
        pdf = new PdfRenderer(pdfFd);
        pdfUri = u;
    }

    private void closePdf() {
        try { if (pdf != null) pdf.close(); } catch (Exception ignored) {}
        try { if (pdfFd != null) pdfFd.close(); } catch (Exception ignored) {}
        pdf = null; pdfFd = null; pdfUri = null;
    }

    // ---------- 영상 ----------
    private void startVideo(Uri u, int my) {
        releaseVideo();
        screen.img.setVisibility(View.GONE);
        screen.img.setImageDrawable(null);
        TextureView t = screen.tex;
        t.setVisibility(View.VISIBLE);
        SurfaceTexture st = t.getSurfaceTexture();
        if (st == null) {
            t.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { if (my == seq) startVideo(u, my); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fitVideo(); }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            });
            return;
        }
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            surface = new Surface(st);
            p.setSurface(surface);
            p.setDataSource(act, u);
            p.setLooping(loop);
            p.setVolume(muted ? 0 : 1, muted ? 0 : 1);
            p.setOnPreparedListener(x -> {
                if (x != mp) return;
                vidW = x.getVideoWidth(); vidH = x.getVideoHeight();
                fitVideo();
                if (!black) playVideo(); else resumeAfterBlack = true;
                emit();
            });
            p.setOnVideoSizeChangedListener((x, w, h) -> { vidW = w; vidH = h; fitVideo(); });
            p.setOnCompletionListener(x -> { playing = false; emit(); });   // 끝나면 마지막 장면에서 멈춤
            p.setOnErrorListener((x, what, extra) -> { fail("영상을 재생할 수 없어요."); releaseVideo(); return true; });
            p.prepareAsync();
        } catch (Exception e) {
            releaseVideo();
            fail("영상을 열 수 없어요. 지워졌거나 옮겨졌을 수 있어요.");
        }
    }

    // 영상 비율 유지: 맞춤 = 검은 여백, 꽉 채움 = 남는 부분 잘림
    private void fitVideo() {
        if (screen == null || vidW <= 0 || vidH <= 0) return;
        TextureView t = screen.tex;
        float W = t.getWidth() > 0 ? t.getWidth() : dispW, H = t.getHeight() > 0 ? t.getHeight() : dispH;
        float s = fill ? Math.max(W / vidW, H / vidH) : Math.min(W / vidW, H / vidH);
        Matrix m = new Matrix();
        m.setScale(vidW * s / W, vidH * s / H, W / 2f, H / 2f);
        t.setTransform(m);
    }

    private void playVideo() { if (mp == null) return; try { mp.start(); playing = true; } catch (Exception ignored) {} }
    private void pauseVideo() { if (mp == null) return; try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) {} playing = false; }

    private void releaseVideo() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} }
        mp = null; playing = false; resumeAfterBlack = false; vidW = vidH = 0;
        if (surface != null) { surface.release(); surface = null; }
    }

    // ---------- 상태 알림 ----------
    private void fail(String msg) { Hub.main.post(() -> { error = msg; emit(); }); }

    void emit() {
        try {
            JSONObject j = new JSONObject();
            if (screen != null) {
                Display d = screen.getDisplay();
                JSONObject dj = new JSONObject();
                dj.put("name", d != null ? d.getName() : "");
                dj.put("w", dispW); dj.put("h", dispH);
                j.put("disp", dj);
            }
            j.put("black", black); j.put("fill", fill); j.put("loop", loop); j.put("mute", muted);
            j.put("video", mp != null); j.put("playing", playing);
            j.put("err", error);
            if ("ppt".equals(type)) j.put("slides", slideCount);
            Hub.emit("onShare", j.toString());
        } catch (Exception ignored) {}
    }

    // ---------- 파일 고르기 ----------
    static final String[] MIMES = {
        "image/*", "video/*", "application/pdf", "text/html", "application/xhtml+xml",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    };

    static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, MIMES);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    /** 고른 파일들의 이름·종류·쪽수·작은 미리보기를 만들어 화면(JS)에 넘김 */
    void onPicked(Intent data) {
        List<Uri> list = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) list.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) list.add(data.getData());
        ContentResolver cr = act.getContentResolver();
        for (Uri u : list) {
            // 앱을 다시 켜도 같은 파일을 열 수 있게 권한 유지
            try { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        }
        worker.execute(() -> {
            JSONArray out = new JSONArray();
            for (Uri u : list) {
                try { out.put(describe(u)); } catch (Exception ignored) {}
            }
            Hub.emit("onShareItems", out.toString());
        });
    }

    void forget(String u) {
        try { act.getContentResolver().releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }

    // pptx(zip) 안의 ppt/slides/slideN.xml 개수 = 장 수
    private static final Pattern SLIDE = Pattern.compile("^ppt/slides/slide\\d+\\.xml$");
    private static int countSlides(ContentResolver cr, Uri u) {
        int n = 0;
        try (InputStream in = cr.openInputStream(u); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (SLIDE.matcher(e.getName()).matches()) n++;
        } catch (Exception ignored) {}
        return n;
    }

    private JSONObject describe(Uri u) throws Exception {
        ContentResolver cr = act.getContentResolver();
        String mime = cr.getType(u);
        if (mime == null) mime = "";
        String name = "";
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        String lower = name == null ? "" : name.toLowerCase();
        String t;
        if (mime.startsWith("image/")) t = "image";
        else if (mime.startsWith("video/")) t = "video";
        else if (mime.equals("application/pdf") || lower.endsWith(".pdf")) t = "pdf";
        else if (mime.contains("html") || lower.endsWith(".html") || lower.endsWith(".htm")) t = "html";
        else if (lower.endsWith(".ppt") || mime.equals("application/vnd.ms-powerpoint")) t = "pptold";   // 옛날 PPT 형식은 못 그림
        else if (mime.contains("presentation") || lower.endsWith(".pptx")) t = "ppt";
        else t = "other";
        JSONObject j = new JSONObject();
        j.put("uri", u.toString()); j.put("name", name); j.put("type", t);
        Bitmap th = null;
        try {
            if (t.equals("image")) {
                th = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, u), (dec, info, s) -> {
                    Size sz = info.getSize();
                    float k = Math.min(1f, 200f / Math.max(sz.getWidth(), sz.getHeight()));
                    dec.setTargetSize(Math.max(1, Math.round(sz.getWidth() * k)), Math.max(1, Math.round(sz.getHeight() * k)));
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else if (t.equals("video")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(act, u);
                    String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (dur != null) j.put("dur", Long.parseLong(dur));
                    th = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 200);
                } finally { try { r.release(); } catch (Exception ignored) {} }
            } else if (t.equals("ppt")) {
                j.put("pages", countSlides(cr, u));
            } else if (t.equals("pdf")) {
                try (ParcelFileDescriptor fd = cr.openFileDescriptor(u, "r"); PdfRenderer pr = new PdfRenderer(fd)) {
                    j.put("pages", pr.getPageCount());
                    try (PdfRenderer.Page p = pr.openPage(0)) {
                        float k = 200f / Math.max(p.getWidth(), p.getHeight());
                        th = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * k)), Math.max(1, Math.round(p.getHeight() * k)), Bitmap.Config.ARGB_8888);
                        th.eraseColor(Color.WHITE);
                        p.render(th, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
            }
        } catch (Exception ignored) {}
        if (th != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            th.compress(Bitmap.CompressFormat.JPEG, 60, bo);
            j.put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
        }
        return j;
    }
}
