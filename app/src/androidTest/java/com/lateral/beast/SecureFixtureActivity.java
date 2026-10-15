package com.lateral.beast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.text.Editable;
import android.text.TextWatcher;

/** A disposable app window, with protection controlled only by this fixture. */
public class SecureFixtureActivity extends Activity {
    public static final int MARKER = Color.rgb(224, 32, 192);
    private TextView view;
    private FrameLayout root;
    private EditText editor;
    private int tick;
    private int taps;
    private int touchDowns;
    private int keys;
    private int keyDowns;
    private int lastDownKey;
    private int lastUpKey;
    private int scrolls;
    private float lastX;
    private float lastY;
    private final Runnable animate = new Runnable() {
        @Override public void run() {
            view.setText("Display protection fixture " + (++tick) + " taps=" + taps + " keys=" + keys);
            view.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            taps = state.getInt("fixture.taps"); touchDowns = state.getInt("fixture.downs");
            keys = state.getInt("fixture.keys"); keyDowns = state.getInt("fixture.keyDowns");
            scrolls = state.getInt("fixture.scrolls");
        }
        view = new TextView(this);
        view.setBackgroundColor(MARKER);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setText("Display protection fixture");
        root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyProtection(getIntent());
        applyEditor(getIntent());
        recordInput();
        view.post(animate);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyProtection(intent);
        applyEditor(intent);
    }

    private void applyEditor(Intent intent) {
        if (!intent.getBooleanExtra("editor", false) || editor != null) return;
        editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setShowSoftInputOnFocus(false);
        editor.setTextColor(Color.WHITE);
        editor.setBackgroundColor(MARKER);
        root.addView(editor, new FrameLayout.LayoutParams(300, 80, Gravity.CENTER));
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { recordInput(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        editor.requestFocus();
    }

    private void applyProtection(Intent intent) {
        if (intent.getBooleanExtra("protected", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { touchDowns++; recordInput(); }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            taps++;
            lastX = event.getX(); lastY = event.getY();
            recordInput();
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) { keyDowns++; lastDownKey=event.getKeyCode(); recordInput(); }
        if (event.getAction() == KeyEvent.ACTION_UP) { keys++; lastUpKey=event.getKeyCode(); recordInput(); }
        return editor != null ? super.dispatchKeyEvent(event) : true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) { scrolls++; recordInput(); }
        return true;
    }

    private void recordInput() {
        getSharedPreferences(getClass() == SecureFixtureActivity.class ? "protected-fixture-input" : "protected-second-input", MODE_PRIVATE).edit()
            .putInt("taps", taps).putInt("keys", keys).putInt("scrolls", scrolls)
            .putInt("touchDowns", touchDowns)
            .putInt("keyDowns", keyDowns)
            .putInt("lastDownKey", lastDownKey).putInt("lastUpKey", lastUpKey)
            .putString("typed", editor == null ? "" : editor.getText().toString())
            .putFloat("x", lastX).putFloat("y", lastY).apply();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("fixture.taps", taps); state.putInt("fixture.downs", touchDowns);
        state.putInt("fixture.keys", keys); state.putInt("fixture.keyDowns", keyDowns);
        state.putInt("fixture.scrolls", scrolls);
    }

    @Override public void onDestroy() {
        view.removeCallbacks(animate);
        super.onDestroy();
    }
}
