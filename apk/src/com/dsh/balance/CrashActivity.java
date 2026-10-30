package com.dsh.balance;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 崩溃显示页：全屏黑底红字，把异常堆栈整段显示出来。
 * 用户截图发回即可定位。没有任何依赖，绝不会二次崩溃。
 */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        String thread = getIntent() == null ? "?" : getIntent().getStringExtra("thread");
        String trace = getIntent() == null ? "(无堆栈)" : getIntent().getStringExtra("trace");
        if (trace == null) trace = "(无堆栈)";

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0A0D12"));
        scroll.setFillViewport(true);

        TextView tv = new TextView(this);
        tv.setPadding(24, 48, 24, 48);
        tv.setTextColor(Color.parseColor("#FF7B7B"));
        tv.setTextSize(13);
        tv.setLineSpacing(4f, 1f);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setText("⚠ App 崩溃了\n\n线程: " + thread + "\n\n" + trace +
                "\n\n（截图这一屏发给我，我就能定位）");
        scroll.addView(tv);
        setContentView(scroll);
    }
}