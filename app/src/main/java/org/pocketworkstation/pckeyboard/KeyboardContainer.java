package org.pocketworkstation.pckeyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;

public class KeyboardContainer extends FrameLayout {
    public KeyboardContainer(Context context) {
        super(context);
        init();
    }

    public KeyboardContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KeyboardContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFitsSystemWindows(true);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        for (int i = 0, ie = getChildCount(); i < ie; i++) {
            View v = getChildAt(i);
            if (v instanceof LatinKeyboardBaseView) {
                ((LatinKeyboardBaseView) v).applyInset(insets.getSystemWindowInsetBottom());
            }
        }
        return super.onApplyWindowInsets(insets);
    }
}
