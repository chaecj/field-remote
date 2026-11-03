package kr.chaeum.fieldremote;

import android.webkit.JavascriptInterface;

/** TV 화면의 PPT 그리기 페이지가 window.TV 로 부르는 기능 (장 수 알림, 오류) */
public final class TvJs {
    private final ScreenShare share;

    TvJs(ScreenShare s) { share = s; }

    @JavascriptInterface public void slides(int n) { Hub.main.post(() -> share.onSlides(n)); }

    @JavascriptInterface public void error(String msg) { Hub.main.post(() -> share.onPptError(msg)); }
}
