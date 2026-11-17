package com.example.budgetapp.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import java.util.List;
import com.example.budgetapp.R;

/** Shared frame for the automatic and tile confirmation windows. */
public final class ConfirmationOverlay extends FrameLayout {
    private View card;
    private NestedScrollView form;
    private int preferredWidth;
    private OneShotPreDrawListener revealRequest;
    private final Rect editorBounds = new Rect();
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanges = (before, current) -> {
        if (current instanceof EditText) scheduleEditorReveal();
    };

    public ConfirmationOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        card = findViewById(R.id.window_card_content);
        preferredWidth = card.getLayoutParams().width;
        form = findViewById(R.id.window_confirmation_form);
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) scheduleEditorReveal();
            return insets;
        });
        ViewCompat.setWindowInsetsAnimationCallback(this,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,
                            List<WindowInsetsAnimationCompat> animations) {
                        return insets;
                    }

                    @Override public void onEnd(WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) scheduleEditorReveal();
                    }
                });
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Keep the usual width on phones and tablets, but never clip on a narrower window.
        card.getLayoutParams().width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? preferredWidth : Math.min(preferredWidth, MeasureSpec.getSize(widthSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanges);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanges);
        if (revealRequest != null) {
            revealRequest.removeListener();
            revealRequest = null;
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // The form can be measured again even when the overlay's outer bounds do not change.
        scheduleEditorReveal();
    }

    private void scheduleEditorReveal() {
        if (revealRequest != null || !isAttachedToWindow() || !(findFocus() instanceof EditText)) return;
        revealRequest = OneShotPreDrawListener.add(this, () -> {
            revealRequest = null;
            View editor = findFocus();
            if (!(editor instanceof EditText) || !editor.isShown() || form.getHeight() == 0) return;
            editor.getDrawingRect(editorBounds);
            form.offsetDescendantRectToMyCoords(editor, editorBounds);
            int top = form.getScrollY() + form.getPaddingTop();
            int bottom = form.getScrollY() + form.getHeight() - form.getPaddingBottom();
            if (editorBounds.bottom > bottom) form.scrollBy(0, editorBounds.bottom - bottom);
            else if (editorBounds.top < top) form.scrollBy(0, editorBounds.top - top);
        });
        postInvalidateOnAnimation();
    }

    public static WindowManager.LayoutParams windowParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT);
        params.dimAmount = 0.5f;
        params.gravity = Gravity.CENTER;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            params.setFitInsetsIgnoringVisibility(false);
        }
        return params;
    }
}
