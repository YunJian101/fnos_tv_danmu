package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.LoginRequest;
import com.fntv.app.api.model.LoginResponseData;
import com.fntv.app.ui.Theme;
import com.fntv.app.ui.Ui;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 登录页（重构版）：一个表单 + 两种登录模式切换。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Login";

    private SharedPreferences prefs;
    private EditText etHost, etUser, etPass;
    private TextView btnLogin;
    private TextView modeHttp, modeFnid, labelHost;

    private boolean isFnIdMode = false;
    private boolean isLoggingIn = false;
    private long loginStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.applySaved(this);
        super.onCreate(savedInstanceState);
        com.fntv.app.api.FnApiManager.initDiskCache(getApplicationContext());
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);

        // 已登录过（token 还在）就直接进首页，不再让用户重登
        String savedToken = prefs.getString("token", "");
        if (!savedToken.isEmpty() && !getIntent().getBooleanExtra("skip_auto_login", false)) {
            FnApiManager.getInstance().setToken(savedToken);
            String host = prefs.getString("host", "");
            if (!host.isEmpty()) FnApiManager.getInstance().updateBaseUrl(host);
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        bindTvInput(etHost);
        bindTvInput(etUser);
        bindTvInput(etPass);
        // 电视端：进页面焦点先落在服务器地址上
        etHost.post(new Runnable() {
            @Override public void run() { etHost.requestFocus(); }
        });
        if (prefs.getBoolean("remember", false) || prefs.getBoolean("fnid_remember", false)) {
            prefs.edit().putBoolean("remember_ui", true).apply();
        }
        LinearLayout rememberBox = findViewById(R.id.rememberBox);
        rememberBox.addView(Ui.text(this, "记住密码", 13, R.color.ui_text, false));
        View rememberSwitch = Ui.switchControl(this, "remember_ui",
                prefs.getBoolean("remember_ui", false), prefs);
        // 保留开关自身的尺寸，只补左边距（原来用 WRAP_CONTENT 覆盖后开关被压成一个圆点）
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) rememberSwitch.getLayoutParams();
        rlp.leftMargin = Ui.dp(this, 12);
        rememberSwitch.setLayoutParams(rlp);
        rememberBox.addView(rememberSwitch);
        btnLogin = findViewById(R.id.btnLogin);
        modeHttp = findViewById(R.id.modeHttp);
        modeFnid = findViewById(R.id.modeFnid);
        labelHost = findViewById(R.id.labelHost);

        bindTvInput(etHost);
        bindTvInput(etUser);
        bindTvInput(etPass);
        etHost.post(new Runnable() {
            @Override public void run() { etHost.requestFocus(); }
        });

        isFnIdMode = "fnid".equals(prefs.getString("login_mode", ""));
        applyMode(false);

        View.OnClickListener switchMode = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean want = (v == modeFnid);
                if (want == isFnIdMode) return;
                saveInput();
                isFnIdMode = want;
                applyMode(true);
            }
        };
        modeHttp.setOnClickListener(switchMode);
        modeFnid.setOnClickListener(switchMode);
        Ui.focusEffect(modeHttp);
        Ui.focusEffect(modeFnid);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });

        // 冷启动自动登录
        if (!getIntent().getBooleanExtra("skip_auto_login", false)) {
            btnLogin.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!prefs.getBoolean("remember_ui", false)) return;
                    String h = prefs.getString(isFnIdMode ? "fnid_host" : "host", "");
                    String u = prefs.getString(isFnIdMode ? "fnid_user" : "user", "");
                    String p = prefs.getString(isFnIdMode ? "fnid_pass" : "pass", "");
                    if (!h.isEmpty() && !u.isEmpty() && !p.isEmpty()) {
                        btnLogin.setText("自动登录中...");
                        doLogin();
                    }
                }
            }, 250);
        }
    }

    /** 切换登录模式：只改文案与输入内容，表单结构不变 */
    private void applyMode(boolean loadValues) {
        modeHttp.setSelected(!isFnIdMode);
        modeFnid.setSelected(isFnIdMode);
        modeHttp.setTypeface(null, isFnIdMode ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        modeFnid.setTypeface(null, isFnIdMode ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        if (isFnIdMode) {
            labelHost.setText("FN ID");
            etHost.setHint("例如 a123456789");
            etHost.setInputType(InputType.TYPE_CLASS_TEXT);
        } else {
            labelHost.setText("服务器地址");
            etHost.setHint("例如 http://192.168.1.1:5666");
            etHost.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        loadInput();
        if (loadValues) {
            prefs.edit().putString("login_mode", isFnIdMode ? "fnid" : "http").apply();
        }
    }

    private void loadInput() {
        if (isFnIdMode) {
            etHost.setText(prefs.getString("fnid_host", ""));
            etUser.setText(prefs.getString("fnid_user", "video"));
            boolean rem = prefs.getBoolean("fnid_remember", false);
            etPass.setText(rem ? prefs.getString("fnid_pass", "") : "");
        } else {
            etHost.setText(prefs.getString("host", "http://192.168.1.1:5666"));
            etUser.setText(prefs.getString("user", "video"));
            boolean rem = prefs.getBoolean("remember", false);
            etPass.setText(rem ? prefs.getString("pass", "") : "");
        }
    }

    private void saveInput() {
        SharedPreferences.Editor e = prefs.edit();
        String host = etHost.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();
        boolean rem = prefs.getBoolean("remember_ui", false);
        if (isFnIdMode) {
            e.putString("fnid_host", host);
            e.putString("fnid_user", user);
            e.putBoolean("fnid_remember", rem);
            if (rem) e.putString("fnid_pass", pass);
            else e.remove("fnid_pass");
        } else {
            e.putString("host", host);
            e.putString("user", user);
            e.putBoolean("remember", rem);
            if (rem) e.putString("pass", pass);
            else e.remove("pass");
        }
        e.apply();
    }

    private void doLogin() {
        if (isLoggingIn) return;
        final String host = etHost.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString().trim();

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "所有字段都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        isLoggingIn = true;
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");
        saveInput();

        if (isFnIdMode) {
            if (!FnIdLoginHelper.isFnId(host)) {
                Toast.makeText(this, "FN ID 格式不正确（6-30 位字符，不含点号和斜杠）", Toast.LENGTH_LONG).show();
                resetLoginState();
                return;
            }
            new FnIdLoginHelper(this, host, user, pass, new FnIdLoginHelper.FnIdCallback() {
                @Override
                public void onSuccess(String token, String domain) {
                    FnApiManager.getInstance().setToken(token);
                    SharedPreferences.Editor e = prefs.edit();
                    e.putString("token", token);
                    e.putString("host", domain);
                    e.putString("login_mode", "fnid");
                    e.putBoolean("_was_fnid", true);
                    e.apply();
                    Log.i(TAG, "FN ID 登录成功 domain=" + domain);
                    startActivity(new Intent(MainActivity.this, HomeActivity.class));
                    finish();
                }

                @Override
                public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "FN ID 登录失败: " + message, Toast.LENGTH_LONG).show();
                            resetLoginState();
                        }
                    });
                }
            }).start();
        } else {
            doLoginHttp(host, user, pass);
        }
    }

    private void doLoginHttp(String host, final String user, final String pass) {
        loginStartTime = System.currentTimeMillis();
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
        final String finalHost = host;
        prefs.edit().putString("host", finalHost).putString("login_mode", "http").apply();

        FnApiManager.getInstance().updateBaseUrl(finalHost);
        FnApiManager.getInstance().getApi().login(new LoginRequest(user, pass))
                .enqueue(new Callback<ApiResponse<LoginResponseData>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<LoginResponseData>> call,
                                           Response<ApiResponse<LoginResponseData>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            if (response.body().code == 0 && response.body().data != null) {
                                FnApiManager.getInstance().setToken(response.body().data.token);
                                prefs.edit().putString("token", response.body().data.token).apply();
                                Log.i(TAG, "HTTP 登录成功 " + (System.currentTimeMillis() - loginStartTime) + "ms");
                                startActivity(new Intent(MainActivity.this, HomeActivity.class));
                                finish();
                                return;
                            }
                            Toast.makeText(MainActivity.this,
                                    "登录失败: " + response.body().msg, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "服务器响应异常，请重试", Toast.LENGTH_LONG).show();
                        }
                        resetLoginState();
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<LoginResponseData>> call, Throwable t) {
                        Log.e(TAG, "网络请求失败: " + t.getMessage(), t);
                        Toast.makeText(MainActivity.this, "网络连接失败: " + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                        resetLoginState();
                    }
                });
    }

    private void resetLoginState() {
        isLoggingIn = false;
        btnLogin.setEnabled(true);
        btnLogin.setText("登 录");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    /** 电视端：聚焦输入框后按确定键弹出软键盘 */
    private void bindTvInput(final android.widget.EditText et) {
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        et.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                et.requestFocus();
                et.setSelection(et.getText().length());
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            }
        });
        et.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
            @Override public void onFocusChange(android.view.View v, boolean has) {
                if (has) et.setSelection(et.getText().length());
            }
        });
    }
    /** 遥控器：焦点在输入框上按确定键 → 弹出软键盘（EditText 自己会吞掉这个键） */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER) {
                View f = getCurrentFocus();
                if (f instanceof android.widget.EditText) {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(f, 0);
                    return true;
                }
            }
            // 输入框会吞掉上下键去移光标，这里改成切换焦点，否则一进输入框就出不来
            if ((k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                View f = getCurrentFocus();
                if (f instanceof android.widget.EditText) {
                    View next = f.focusSearch(k == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            ? View.FOCUS_DOWN : View.FOCUS_UP);
                    if (next != null && next.requestFocus()) {
                        android.view.inputmethod.InputMethodManager imm2 =
                                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm2 != null) imm2.hideSoftInputFromWindow(f.getWindowToken(), 0);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
