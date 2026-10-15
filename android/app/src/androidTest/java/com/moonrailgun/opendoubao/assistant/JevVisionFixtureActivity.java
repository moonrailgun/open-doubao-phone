package com.moonrailgun.opendoubao.assistant;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pixel-only targets and text; Java keeps the standalone test APK independent of Kotlin runtime. */
public class JevVisionFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("视觉测试：等待操作");
        status.setTextSize(22);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 160, 24, 0);
        column.addView(status);
        column.addView(new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final String code = getIntent().getStringExtra("code");
            final boolean left = getIntent().getBooleanExtra("left", true);
            int taps = 0;
            { setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.BLACK);
                paint.setTextSize(70);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("编号 " + code, getWidth() / 2f, getHeight() * .2f, paint);
                paint.setColor(Color.RED);
                float red = getWidth() * (left ? .75f : .25f);
                canvas.drawRect(red - 100, getHeight() * .6f - 100, red + 100, getHeight() * .6f + 100, paint);
                paint.setColor(taps == 0 ? Color.rgb(0, 160, 70) : Color.GRAY);
                canvas.drawCircle(getWidth() * (left ? .25f : .75f), getHeight() * .6f, 100, paint);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    performClick();
                    float dx = event.getX() - getWidth() * (left ? .25f : .75f);
                    float dy = event.getY() - getHeight() * .6f;
                    if (dx * dx + dy * dy <= 10000) {
                        status.setText("绿色圆形已点击 " + (++taps) + " 次");
                        invalidate();
                    } else status.setText("点击错误：未命中绿色圆形");
                }
                return true;
            }
            @Override public boolean performClick() { super.performClick(); return true; }
        }, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
    }
}
