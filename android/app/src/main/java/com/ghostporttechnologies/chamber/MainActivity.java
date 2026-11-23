package com.ghostporttechnologies.chamber;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

/**
 * Keeps Chamber clear of the status bar, camera cutout, gesture bar and keyboard.
 *
 * Chamber's web page asks to be drawn edge to edge (viewport-fit=cover) but its top bar doesn't yet leave room for
 * the status bar, so on phones the header's buttons ended up under the clock and battery icons. Until the web app
 * handles safe areas itself (Chamber ticket T-0338), the app pads the WebView natively and consumes the insets, so
 * the page sees zero insets and can't double the padding after T-0338 ships.
 * Capacitor's own handling is off for this reason (capacitor.config.json: SystemBars.insetsHandling = "disable").
 */
public class MainActivity extends BridgeActivity {

    private static final int CHAMBER_BACKGROUND = Color.parseColor("#0A0D0B");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw behind the bars ourselves, paint them Chamber's background, light icons on the dark bars.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().getDecorView().setBackgroundColor(CHAMBER_BACKGROUND);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        WebView webView = getBridge().getWebView();
        View container = webView.getParent() instanceof ViewGroup ? (View) webView.getParent() : webView;
        container.setBackgroundColor(CHAMBER_BACKGROUND);

        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            Insets safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            // The keyboard replaces the gesture bar at the bottom while it's open, so the message box stays visible.
            view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(container);
    }
}
