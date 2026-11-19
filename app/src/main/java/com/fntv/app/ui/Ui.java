package com.fntv.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fntv.app.R;
import com.fntv.app.RoundedImageView;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.util.SimpleImageLoader;

/**
 * 大屏 UI 组件库（全部自绘，不依赖系统控件外观）：
 * 圆角卡片、胶囊、开关、滑杆、对话框、海报卡、宽幅卡、媒体库大卡。
 * 所有颜色取自 ui_* 令牌，白天 / 黑夜两套 values 自动切换。
 */
public final class Ui {

    public static final int CARD_RADIUS = 18;
    public static final int INNER_RADIUS = 14;

    private Ui() {}

    private static float sScale = 1f;

    /** 从偏好读取界面缩放并应用（0 标准 / 1 大 / 2 特大） */
    /** 当前是否深色主题 */
    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static void applyScale(Context c) {
        int idx = c.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                .getInt("ui_scale", 0);
        setScale(idx == 1 ? 1.12f : (idx == 2 ? 1.26f : 1f));
    }

    public static void setScale(float scale) {
        sScale = (scale <= 0f) ? 1f : scale;
    }

    public static int dp(Context c, float v) {
        v = v * sScale;
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static int color(Context c, int res) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return c.getResources().getColor(res, c.getTheme());
        }
        return c.getResources().getColor(res);
    }

    public static GradientDrawable round(int fill, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** 遥控器焦点：只靠描边，不做放大（放大后描边会被父容器裁掉，看起来缺一块） */
    public static void focusEffect(final View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                // 描边由 selector 负责；这里只保证不缩放
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
        });
    }

    public static void loadImageFade(ImageView iv, String url) {
        if (url == null || url.isEmpty()) return;
        SimpleImageLoader.loadFade(url, iv, FnApiManager.getInstance().getClient());
    }

    public static void loadImage(ImageView iv, String url) {
        SimpleImageLoader.load(url, iv, FnApiManager.getInstance().getClient());
    }

    public static String posterUrl(String baseUrl, String path) {
        if (path == null || path.isEmpty()) return null;
        String p = path.startsWith("/") ? path : "/" + path;
        return baseUrl + "/v/api/v1/sys/img" + p + "?w=400";
    }

    public static String backdropUrl(String baseUrl, String path) {
        if (path == null || path.isEmpty()) return null;
        String p = path.startsWith("/") ? path : "/" + path;
        return baseUrl + "/v/api/v1/sys/img" + p + "?w=1280";
    }

    // ---------------- 文本 ----------------

    public static TextView text(Context c, String s, float sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color(c, colorRes));
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 区块标题：22sp 粗体，右侧「更多」为 13sp 次要色 */
    public static LinearLayout sectionHeader(Context c, String title, String action, View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 2), dp(c, 14), dp(c, 2), dp(c, 10));
        row.setClipChildren(false);

        row.addView(text(c, title, 22, R.color.ui_text, true));

        if (action != null) {
            TextView a = text(c, action, 13, R.color.ui_text3, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 18);
            a.setLayoutParams(lp);
            a.setGravity(Gravity.CENTER);
            a.setBackgroundResource(R.drawable.ui_pill);
            a.setFocusable(true);
            a.setPadding(dp(c, 16), dp(c, 7), dp(c, 16), dp(c, 7));
            if (onAction != null) a.setOnClickListener(onAction);
            focusEffect(a);
            row.addView(a);
        }
        return row;
    }

    /** 胶囊：选中/聚焦为浅底 + 强调色描边与文字 */
    public static TextView pill(Context c, String label, boolean selected) {
        TextView tv = text(c, label, 13, R.color.ui_text2, selected);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tv.setTextColor(c.getResources().getColorStateList(R.color.ui_pill_text, c.getTheme()));
        } else {
            tv.setTextColor(c.getResources().getColorStateList(R.color.ui_pill_text));
        }
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
        tv.setBackgroundResource(R.drawable.ui_pill);
        tv.setSelected(selected);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        return tv;
    }

    public static TextView primaryButton(Context c, String label, int wDp) {
        TextView tv = text(c, label, 14, R.color.ui_on_accent, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.ui_btn_primary);
        tv.setFocusable(true);
        tv.setPadding(dp(c, 24), dp(c, 11), dp(c, 24), dp(c, 11));
        if (wDp > 0) tv.setLayoutParams(new LinearLayout.LayoutParams(
                dp(c, wDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        focusEffect(tv);
        return tv;
    }

    public static TextView ghostButton(Context c, String label) {
        TextView tv = text(c, label, 14, R.color.ui_text, false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.ui_btn_ghost);
        tv.setFocusable(true);
        tv.setPadding(dp(c, 22), dp(c, 11), dp(c, 22), dp(c, 11));
        focusEffect(tv);
        return tv;
    }

    /** 自绘滑杆：改变时把 0-100 映射成 float 写到另一个 key（供播放器读取） */
    public static View sliderControlLinked(final Context c, final String key, int initial,
                                           final android.content.SharedPreferences prefs,
                                           final String targetKey, final float min, final float max) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);

        final int wPx = dp(c, 220);
        final FrameLayout track = new FrameLayout(c);
        track.setLayoutParams(new LinearLayout.LayoutParams(wPx, dp(c, 4)));
        final View bg = new View(c);
        track.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final View fill = new View(c);
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView val = text(c, "", 13, R.color.ui_text2, false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                dp(c, 60), ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(c, 16);
        val.setLayoutParams(vlp);
        val.setGravity(Gravity.RIGHT);
        row.addView(track);
        row.addView(val);

        final int[] v = {Math.max(0, Math.min(100, initial))};
        final Runnable render = new Runnable() {
            @Override public void run() {
                GradientDrawable t = round(color(c, R.color.ui_surface2), dp(c, 2));
                if (row.hasFocus()) t.setStroke(dp(c, 1), color(c, R.color.ui_accent));
                bg.setBackground(t);
                fill.setBackground(round(color(c, R.color.ui_accent), dp(c, 2)));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                lp.width = (int) (wPx * v[0] / 100f);
                fill.setLayoutParams(lp);
                val.setText(v[0] + "%");
            }
        };
        render.run();

        final Runnable persist = new Runnable() {
            @Override public void run() {
                prefs.edit().putInt(key, v[0]).apply();
                float f = min + (max - min) * v[0] / 100f;
                prefs.edit().putFloat(targetKey, f).apply();
            }
        };

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean has) { render.run(); }
        });
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    v[0] = Math.max(0, v[0] - 5);
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    v[0] = Math.min(100, v[0] + 5);
                } else {
                    return false;
                }
                render.run();
                persist.run();
                return true;
            }
        });
        return row;
    }

    /** 自绘滑杆：遥控器左右键调节，焦点时描边 */
    public static View sliderControl(final Context c, final String key, int initial,
                                     final android.content.SharedPreferences prefs) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);

        final int wPx = dp(c, 220);
        final FrameLayout track = new FrameLayout(c);
        track.setLayoutParams(new LinearLayout.LayoutParams(wPx, dp(c, 4)));

        final View bg = new View(c);
        track.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final View fill = new View(c);
        track.addView(fill, new FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView val = text(c, "", 13, R.color.ui_text2, false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                dp(c, 60), ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(c, 16);
        val.setLayoutParams(vlp);
        val.setGravity(Gravity.RIGHT);

        row.addView(track);
        row.addView(val);

        final int[] v = {Math.max(0, Math.min(100, initial))};
        final Runnable render = new Runnable() {
            @Override
            public void run() {
                GradientDrawable t = round(color(c, R.color.ui_surface2), dp(c, 2));
                if (row.hasFocus()) t.setStroke(dp(c, 1), color(c, R.color.ui_accent));
                bg.setBackground(t);
                fill.setBackground(round(color(c, R.color.ui_accent), dp(c, 2)));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                lp.width = (int) (wPx * v[0] / 100f);
                fill.setLayoutParams(lp);
                val.setText(v[0] + "%");
            }
        };
        render.run();

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                render.run();
            }
        });
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                int step = 5;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    v[0] = Math.max(0, v[0] - step);
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    v[0] = Math.min(100, v[0] + step);
                } else {
                    return false;
                }
                prefs.edit().putInt(key, v[0]).apply();
                render.run();
                return true;
            }
        });
        return row;
    }

    /** 自绘开关（不用系统 Switch） */
    public static View switchControl(final Context c, final String key, boolean initial,
                                     final android.content.SharedPreferences prefs) {
        final LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setGravity(Gravity.CENTER_VERTICAL);
        track.setFocusable(true);
        track.setFocusableInTouchMode(false);
        final int h = dp(c, 34), pad = dp(c, 4);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 62), h));
        track.setPadding(pad, pad, pad, pad);

        final View knob = new View(c);
        final int knobSize = h - pad * 2;
        knob.setLayoutParams(new LinearLayout.LayoutParams(knobSize, knobSize));
        track.addView(knob);

        final boolean[] on = {initial};
        final Runnable render = new Runnable() {
            @Override
            public void run() {
                boolean night = isNight(c);
                // 关闭态加深底色 + 灰描边，避免"看着像没勾"
                int offColor = night ? 0xFF414D63 : 0xFFAFC0D6;
                int offLine  = night ? 0xFF55627A : 0xFF9FB0C6;
                // 开启态用绿色，避免和焦点框的强调蓝撞色、分不清"选中"和"聚焦"
                int onColor = 0xFF22A06B;
                GradientDrawable bg = round(on[0] ? onColor : offColor, h / 2);
                bg.setStroke(dp(c, 1), on[0] ? onColor : offLine);
                if (track.hasFocus()) {
                    // 强调色描边：绿/灰轨道上都清晰（白色会在浅色卡片上看不见）
                    bg.setStroke(dp(c, 3), color(c, R.color.ui_accent));
                }
                track.setBackground(bg);
                GradientDrawable knobBg = round(Color.WHITE, knobSize / 2);
                knobBg.setStroke(dp(c, 1), night ? 0xFF55627A : 0xFF8FA2B9);
                knob.setBackground(knobBg);
                // 用容器 gravity 定位，避免单个子 View 的 gravity 在部分机型上不生效
                track.setGravity((on[0] ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            }
        };
        render.run();

        track.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                on[0] = !on[0];
                prefs.edit().putBoolean(key, on[0]).apply();
                render.run();
            }
        });
        track.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                render.run();

            }
        });
        return track;
    }

    // ---------------- 卡片 ----------------

    /** 海报容器：没图时显示占位首字，避免出现灰块 */
    public static FrameLayout posterBox(Context c, String url, String fallback, int radiusDp) {
        FrameLayout box = new FrameLayout(c);

        RoundedImageView img = new RoundedImageView(c);
        img.setCornerRadius(radiusDp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(color(c, R.color.ui_surface2));
        box.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (url != null && !url.isEmpty()) {
            loadImage(img, url);
        } else {
            TextView ph = text(c, initialOf(fallback), 22, R.color.ui_text3, true);
            ph.setGravity(Gravity.CENTER);
            box.addView(ph, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return box;
    }

    private static String initialOf(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, 1);
    }

    /** 右上角圆标（集数 / 已看），text 为空则不显示 */
    private static View cornerBadge(Context c, String text) {
        TextView tv = text(c, text, 12, R.color.ui_on_accent, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.ui_badge_ep);
        tv.setMinWidth(dp(c, 28));
        tv.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        return tv;
    }

    /** 右下角评分角标 */
    private static View scoreBadge(Context c, String text) {
        TextView tv = text(c, text, 11, R.color.ui_on_accent, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.ui_badge_score);
        tv.setMinWidth(dp(c, 42));
        tv.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        return tv;
    }

    /** 海报卡（旧签名，无角标） */
    public static View posterCard(Context c, String title, String sub, String imgUrl,
                                  int wDp, int posterHDp, final View.OnClickListener onClick) {
        return posterCard(c, title, sub, imgUrl, null, null, wDp, posterHDp, onClick);
    }

    /** 海报卡：2:3 圆角图 + 右上集数圆标 + 右下评分角标 + 标题/副标题 */
    public static View posterCard(Context c, String title, String sub, String imgUrl,
                                  String badgeText, String scoreText,
                                  int wDp, int posterHDp, final View.OnClickListener onClick) {
        FrameLayout card = new FrameLayout(c);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setBackgroundResource(R.drawable.ui_card_plain);
        int pad = dp(c, 6);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        FrameLayout art = new FrameLayout(c);
        art.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, posterHDp)));
        art.addView(posterBox(c, imgUrl, title, INNER_RADIUS),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));


        if (badgeText != null && !badgeText.isEmpty()) {
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.TOP | Gravity.RIGHT;
            blp.topMargin = dp(c, 8);
            blp.rightMargin = dp(c, 8);
            art.addView(cornerBadge(c, badgeText), blp);
        }

        col.addView(art);

        TextView t = text(c, title == null ? "" : title, 13, R.color.ui_text, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(c, 8);
        t.setLayoutParams(tlp);
        col.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11, R.color.ui_text3, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 4);
            s.setLayoutParams(slp);
            col.addView(s);
        }

        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), ViewGroup.LayoutParams.WRAP_CONTENT));

        if (onClick != null) card.setOnClickListener(onClick);
        focusEffect(card);
        return card;
    }

    /** 宽幅卡（旧签名，用进度条代替剩余时长角标） */
    public static View wideCard(Context c, String title, String sub, String imgUrl, int pct,
                                int wDp, int hDp, final View.OnClickListener onClick) {
        return wideCard(c, title, sub, imgUrl, null, pct, wDp, hDp, onClick);
    }

    /** 宽幅卡：16:9 缩略图 + 左下「剩余 xx」角标 + 观看进度细条 */
    public static View wideCard(Context c, String title, String sub, String imgUrl, String badgeText,
                                int pct, int wDp, int hDp, final View.OnClickListener onClick) {
        FrameLayout card = new FrameLayout(c);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setBackgroundResource(R.drawable.ui_card_plain);
        int pad = dp(c, 6);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        FrameLayout thumbWrap = new FrameLayout(c);
        thumbWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, hDp)));

        thumbWrap.addView(posterBox(c, imgUrl, title, INNER_RADIUS),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));


        if (badgeText != null && !badgeText.isEmpty()) {
            TextView b = text(c, badgeText, 11, R.color.ui_badge_text, false);
            b.setTag("fntv_badge");   // 供局部更新时定位角标文字
            b.setBackgroundResource(R.drawable.ui_badge_time);
            b.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            blp.leftMargin = dp(c, 10);
            blp.bottomMargin = dp(c, 10);
            thumbWrap.addView(b, blp);
        }

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 3));
        blp.gravity = Gravity.BOTTOM;
        blp.leftMargin = dp(c, 10);
        blp.rightMargin = dp(c, 10);
        blp.bottomMargin = dp(c, 6);
        bar.setLayoutParams(blp);
        int p = Math.max(0, Math.min(100, pct));
        if (p > 0) {
            View done = new View(c);
            done.setBackground(round(color(c, R.color.ui_accent), dp(c, 2)));
            bar.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, p));
        }
        if (p < 100) {
            View rest = new View(c);
            rest.setBackground(round(0x55FFFFFF, dp(c, 2)));
            bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - p));
        }
        thumbWrap.addView(bar);
        col.addView(thumbWrap);

        TextView t = text(c, title == null ? "" : title, 15, R.color.ui_text, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(c, 8);
        t.setLayoutParams(tlp);
        col.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11, R.color.ui_text2, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(c, 4);
            s.setLayoutParams(slp);
            col.addView(s);
        }

        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), ViewGroup.LayoutParams.WRAP_CONTENT));

        if (onClick != null) card.setOnClickListener(onClick);
        focusEffect(card);
        return card;
    }

    /** 媒体库大卡：深色渐变 + 台标色块 + 中英双行，替代原来的小胶囊 */
    public static View libCard(Context c, String zh, String en, int markColor,
                               int wDp, int hDp, final View.OnClickListener onClick) {
        FrameLayout card = new FrameLayout(c);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setBackgroundResource(R.drawable.ui_lib_card);
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 18), 0, dp(c, 18), 0);

        // 台标：圆角三角，纯色
        FrameLayout mark = new FrameLayout(c);
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));
        View triangle = new View(c);
        GradientDrawable tri = new GradientDrawable();
        tri.setShape(GradientDrawable.RECTANGLE);
        tri.setColor(markColor);
        tri.setCornerRadius(dp(c, 10));
        triangle.setBackground(tri);
        mark.addView(triangle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(mark);

        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        // 用剩余宽度：标题不再被挤成一个字
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(c, 14);
        txt.setLayoutParams(tlp);

        TextView zhv = text(c, zh, 17, R.color.ui_hero_text, true);
        zhv.setSingleLine(true);
        zhv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(zhv);
        if (en != null && !en.isEmpty()) {
            TextView env = text(c, en, 11, R.color.ui_hero_text2, false);
            env.setAlpha(0.65f);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(c, 3);
            env.setLayoutParams(elp);
            txt.addView(env);
        }
        row.addView(txt);

        card.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (onClick != null) card.setOnClickListener(onClick);
        // 媒体库大卡自带焦点态：描边用 accent
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {

                if (has) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.RECTANGLE);
                    bg.setColor(color(v.getContext(), R.color.ui_lib_start));
                    bg.setCornerRadius(dp(v.getContext(), 20));
                    bg.setStroke(dp(v.getContext(), 2), color(v.getContext(), R.color.ui_accent));
                    v.setBackground(bg);
                } else {
                    v.setBackgroundResource(R.drawable.ui_lib_card);
                }
            }
        });
        return card;
    }

    public static android.widget.HorizontalScrollView rail(Context c, LinearLayout row) {
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(c);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(c, 2), dp(c, 6), dp(c, 2), dp(c, 6));
        hsv.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hsv;
    }

    public static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    public static View spacer(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    public static void grid(Context c, LinearLayout container, java.util.List<View> cards,
                            int minCardDp, int gapDp) {
        container.removeAllViews();
        int widthDp = c.getResources().getConfiguration().screenWidthDp;
        int cols = Math.max(3, widthDp / minCardDp);
        int gap = dp(c, gapDp);
        for (int i = 0; i < cards.size(); i += cols) {
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            line.setClipToPadding(false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.bottomMargin = gap;
            line.setLayoutParams(llp);
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                if (idx < cards.size()) {
                    View card = cards.get(idx);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    clp.rightMargin = gap / 2;
                    clp.leftMargin = gap / 2;
                    card.setLayoutParams(clp);
                    line.addView(card);
                } else {
                    View fill = new View(c);
                    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, 1, 1f);
                    flp.rightMargin = gap / 2;
                    flp.leftMargin = gap / 2;
                    fill.setLayoutParams(flp);
                    line.addView(fill);
                }
            }
            container.addView(line);
        }
    }

    /** 统一风格的设置行 */
    public static LinearLayout settingRow(Context c, String title, String desc, View control) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.ui_card);
        row.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(c, 12);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(c, title, 16, R.color.ui_text, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = text(c, desc, 12, R.color.ui_text3, false);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(c, 5);
            d.setLayoutParams(dlp);
            col.addView(d);
        }
        row.addView(col);
        if (control != null) row.addView(control);
        return row;
    }

    /** 统一风格对话框 */
    public static Dialog dialog(Activity a, String title, View content,
                                String okText, final Runnable onOk, String cancelText) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(color(a, R.color.ui_surface), dp(a, 20));
        bg.setStroke(dp(a, 1), color(a, R.color.ui_line));
        root.setBackground(bg);
        root.setPadding(dp(a, 26), dp(a, 22), dp(a, 26), dp(a, 20));

        if (title != null && !title.isEmpty()) {
            root.addView(text(a, title, 18, R.color.ui_text, true));
        }
        if (content != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(a, 14);
            content.setLayoutParams(clp);
            root.addView(content);
        }

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(a, 20);
        btns.setLayoutParams(blp);

        if (cancelText != null) {
            TextView cancel = ghostButton(a, cancelText);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(a, 12);
            cancel.setLayoutParams(lp);
            btns.addView(cancel);
        }
        if (okText != null) {
            TextView ok = primaryButton(a, okText, 0);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (onOk != null) onOk.run();
                }
            });
            btns.addView(ok);
        }
        root.addView(btns);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(a, 480), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    /** 统一返回按钮：圆形浅底 + 返回图标，全 App 一致 */
    public static View backButton(Context c, final View.OnClickListener onClick) {
        ImageView iv = new ImageView(c);
        iv.setBackgroundResource(R.drawable.ui_back_chip);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        iv.setFocusable(true);
        iv.setContentDescription("返回");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            iv.setElevation(dp(c, 4));
        }
        iv.setOnClickListener(onClick);
        focusEffect(iv);
        return iv;
    }

    /** 播放器里用的深色弹窗（不跟随浅色主题，避免白卡刺眼） */
    public static Dialog dialogDark(Activity a, String title, View content, String cancelText) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(0xFF141A24, dp(a, 20));
        bg.setStroke(dp(a, 1), 0xFF232B39);
        root.setBackground(bg);
        root.setPadding(dp(a, 26), dp(a, 22), dp(a, 26), dp(a, 18));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTextColor(0xFFF3F6FA);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        root.addView(t);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(a, 14);
        content.setLayoutParams(clp);
        root.addView(content);

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(a, 18);
        btns.setLayoutParams(blp);

        if (cancelText != null) {
            TextView cancel = new TextView(a);
            cancel.setText(cancelText);
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cancel.setTextColor(0xFFD8E4F6);
            cancel.setGravity(Gravity.CENTER);
            // 用带焦点态的 selector，否则聚焦时看不到选择框
            cancel.setBackgroundResource(R.drawable.ui_card_plain);
            cancel.setPadding(dp(a, 24), dp(a, 11), dp(a, 24), dp(a, 11));
            cancel.setFocusable(true);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            focusEffect(cancel);
            btns.addView(cancel);
        }
        root.addView(btns);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(a, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    public static void toast(Activity a, String msg) {
        android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "00:00";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 「剩余 1 时 17 分」这种角标文案 */
    public static String remainingText(long remainSeconds) {
        if (remainSeconds <= 0) return "未开始";
        long h = remainSeconds / 3600;
        long m = (remainSeconds % 3600) / 60;
        long s = remainSeconds % 60;
        if (h > 0) return "剩余 " + h + " 时 " + m + " 分";
        if (m > 0) return s > 0 ? "剩余 " + m + " 分 " + s + " 秒" : "剩余 " + m + " 分";
        return "剩余 " + s + " 秒";
    }
}
