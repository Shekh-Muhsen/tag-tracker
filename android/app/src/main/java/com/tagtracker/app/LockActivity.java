package com.tagtracker.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Local app lock. Blocks the app until the phone owner enters the app password.
 * The password and hint live only on THIS phone (SharedPreferences) — never synced to
 * Google or Drive. This is separate from the Google sign-in.
 */
public class LockActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TagApp.hasAppLock(this)) {
            TagApp.appUnlocked = true;
            finish();
            return;
        }
        setTitle("Tag Tracker");

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Enter app password");
        title.setTextSize(20);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        EditText input = new EditText(this);
        input.setHint("App password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(input, wide());

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        root.addView(unlock, wide());

        TextView hint = new TextView(this);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        Button forgot = new Button(this);
        forgot.setText("Forgot? Show hint");
        forgot.setAllCaps(false);
        forgot.setBackground(null);
        forgot.setOnClickListener(v -> {
            String h = TagApp.appHint(this);
            hint.setText(h.isEmpty() ? "No hint was set for this phone." : "Hint: " + h);
        });
        root.addView(forgot);

        Runnable tryUnlock = () -> {
            if (TagApp.checkAppLock(this, input.getText().toString())) {
                TagApp.appUnlocked = true;
                finish();
            } else {
                input.setText("");
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
            }
        };
        unlock.setOnClickListener(v -> tryUnlock.run());
        input.setOnEditorActionListener((v, a, e) -> {
            tryUnlock.run();
            return true;
        });

        setContentView(root);
    }

    private LinearLayout.LayoutParams wide() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(280), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        // Don't let Back bypass the lock — just leave the app.
        moveTaskToBack(true);
    }
}
