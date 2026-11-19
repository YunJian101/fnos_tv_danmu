package com.fntv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.util.Log;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayLinkResponse;
import com.fntv.app.api.model.StreamResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;

/** 画质选择辅助类 */
public class QualitySelectHelper {

    private static final String TAG = "QualitySelect";
    private final Activity activity;
    private final FnApiManager apiManager;
    private final SharedPreferences prefs;
    private final QualityCallback qCallback;
    private List<QualityOption> options;
    private int selectedIndex = 0;
    private StreamResponse streamData; // 缓存的 stream 响应（用于 play/play 请求）

    /** 画质选项 */
    public static class QualityOption {
        public final String label;
        public final String resolution;
        public final int bitrate;
        public final boolean progressive;
        public final boolean isM3u8;
        public final boolean isOriginal;

        public QualityOption(String label, String resolution, int bitrate, boolean progressive, boolean isM3u8, boolean isOriginal) {
            this.label = label;
            this.resolution = resolution;
            this.bitrate = bitrate;
            this.progressive = progressive;
            this.isM3u8 = isM3u8;
            this.isOriginal = isOriginal;
        }
    }

    /** 切换回调 */
    public interface QualityCallback {
        void onQualityChanged(int level);
        void onPlayLinkChanged(String playLink, String resolution, int bitrate);
        String getMediaGuid();
        String getAccount();
        long getPlaybackPosition(); // 当前播放进度（秒）
    }

    public QualitySelectHelper(Activity activity, FnApiManager apiManager, SharedPreferences prefs, QualityCallback qCallback) {
        this.activity = activity;
        this.apiManager = apiManager;
        this.prefs = prefs;
        this.qCallback = qCallback;
        this.selectedIndex = prefs.getInt("stream_quality_idx_" + qCallback.getMediaGuid(), 0);
    }

    /** 获取当前选项 label */
    public String getCurrentLabel() {
        if (options != null && selectedIndex >= 0 && selectedIndex < options.size()) {
            return options.get(selectedIndex).label;
        }
        return "原画";
    }

    /** 加载画质列表并显示弹窗 */
    public void showQualityDialog() {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        body.put("header", header);
        body.put("media_guid", qCallback.getMediaGuid());
        body.put("level", 1);
        String account = qCallback.getAccount();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            body.put("ip", sb.toString());
        } catch (Exception e) {
            body.put("ip", "");
        }
        body.put("nonce", String.valueOf(100000 + (int) (Math.random() * 900000)));

        apiManager.getApi().getStream(body).enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
            @Override
            public void onResponse(retrofit2.Call<ApiResponse<StreamResponse>> call,
                                   retrofit2.Response<ApiResponse<StreamResponse>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.qualities == null
                        || response.body().data.qualities.isEmpty()) {
                    Log.w(TAG, "画质列表为空");
                    if (activity != null && !activity.isFinishing()) {
                        com.fntv.app.ui.Ui.dialog(activity, "画质选择",
                                com.fntv.app.ui.Ui.text(activity, "暂无可切换的画质", 15,
                                        com.fntv.app.R.color.ui_text2, false),
                                null, null, "关闭");
                    }
                    return;
                }
                streamData = response.body().data;
                options = buildOptions(streamData.qualities);
                showDialog();
            }

            @Override
            public void onFailure(Call<ApiResponse<StreamResponse>> call, Throwable t) {
                Log.e(TAG, "获取画质失败: " + t.getMessage());
            }
        });
    }

    /** 构建画质选项 */
    private List<QualityOption> buildOptions(List<StreamResponse.Quality> qualities) {
        Map<String, List<StreamResponse.Quality>> groups = new HashMap<>();
        for (StreamResponse.Quality q : qualities) {
            if (q.resolution == null || q.resolution.isEmpty()) continue;
            if (!groups.containsKey(q.resolution)) groups.put(q.resolution, new ArrayList<StreamResponse.Quality>());
            groups.get(q.resolution).add(q);
        }

        List<String> sortedRes = new ArrayList<>(groups.keySet());
        Collections.sort(sortedRes, (a, b) -> resToNum(b) - resToNum(a));

        List<QualityOption> result = new ArrayList<>();
        String highestRes = sortedRes.isEmpty() ? null : sortedRes.get(0);
        result.add(new QualityOption("原画", "", 0, true, false, true));

        for (String res : sortedRes) {
            List<StreamResponse.Quality> list = groups.get(res);
            boolean isHighest = res.equals(highestRes);
            if (isHighest && list.size() <= 1) continue;
            Collections.sort(list, (a, b) -> b.bitrate - a.bitrate);
            StreamResponse.Quality pick = isHighest ? list.get(1) : list.get(0);
            result.add(new QualityOption(formatResLabel(res), res, pick.bitrate, pick.progressive, pick.isM3u8, false));
        }
        return result;
    }

    /** 分辨率转可比对数字（4K→2160, 1080→1080, 720→720） */
    private static int resToNum(String res) {
        if (res == null) return 0;
        String up = res.toUpperCase();
        if (up.contains("K")) {
            // "4K" / "4k" → 2160
            try {
                int k = Integer.parseInt(up.replaceAll("[^0-9]", ""));
                return k * 540; // 4K ≈ 2160
            } catch (Exception e) { return 0; }
        }
        try { return Integer.parseInt(res.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    /** 分辨率显示标签（4K→"4K", 1080→"1080p"） */
    private static String formatResLabel(String res) {
        if (res == null) return "";
        String up = res.toUpperCase();
        if (up.contains("K")) return up; // "4K" → "4K"
        return res + "p";
    }

    private boolean autoTranscodeTried = false;

    /** 转码流中断后重连：重新请求一条新链接（带当前播放位置） */
    public void retryTranscodeLink() {
        autoTranscodeTried = false;
        transcodeFallback();
    }

    /** 设备解码不了原片时，自动请求 1080P H.264 转码流 */
    public void transcodeFallback() {
        if (autoTranscodeTried) return;
        autoTranscodeTried = true;
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        body.put("header", header);
        body.put("media_guid", qCallback.getMediaGuid());
        body.put("level", 1);
        String account = qCallback.getAccount();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            body.put("ip", sb.toString());
        } catch (Exception e) {
            body.put("ip", "");
        }
        body.put("nonce", String.valueOf(100000 + (int) (Math.random() * 900000)));
        apiManager.getApi().getStream(body).enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
            @Override
            public void onResponse(retrofit2.Call<ApiResponse<StreamResponse>> call,
                                   retrofit2.Response<ApiResponse<StreamResponse>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) {
                    Log.w(TAG, "转码兜底：取流为空");
                    return;
                }
                streamData = response.body().data;
                doTranscode();
            }

            @Override
            public void onFailure(retrofit2.Call<ApiResponse<StreamResponse>> call, Throwable t) {
                Log.e(TAG, "转码兜底失败: " + t.getMessage());
            }
        });
    }

    private void doTranscode() {
        if (streamData == null || qCallback.getMediaGuid() == null) return;
        Map<String, Object> body = new HashMap<>();
        body.put("media_guid", qCallback.getMediaGuid());
        if (streamData.videoStream != null) {
            body.put("video_guid", streamData.videoStream.guid != null ? streamData.videoStream.guid : "");
        }
        body.put("video_encoder", "h264");
        // 按源分辨率/源码率转码：不降分辨率、不降码率
        if (streamData.videoStream != null && streamData.videoStream.height > 0) {
            int h = streamData.videoStream.height;
            String label = h >= 2000 ? "2160p" : h >= 1400 ? "1440p"
                    : h >= 1000 ? "1080p" : h >= 700 ? "720p" : "480p";
            body.put("resolution", label);
        }
        if (streamData.videoStream != null && streamData.videoStream.bps > 0) {
            body.put("bitrate", streamData.videoStream.bps);   // 源码率
        }
        body.put("startTimestamp", qCallback.getPlaybackPosition());
        if (streamData.audioStreams != null && !streamData.audioStreams.isEmpty()) {
            StreamResponse.AudioStreamInfo a = streamData.audioStreams.get(0);
            // 输出编码固定 aac：服务端不接受 dts/truehd 这类作为转码输出，会直接拒绝
            body.put("audio_encoder", "aac");
            body.put("audio_guid", a.guid != null ? a.guid : "");
            body.put("channels", a.channels > 2 ? 2 : a.channels);
        }
        Log.d(TAG, "转码兜底 play/play: " + new com.google.gson.Gson().toJson(body));
        apiManager.getApi().getPlayLink(body).enqueue(new retrofit2.Callback<ApiResponse<PlayLinkResponse>>() {
            @Override
            public void onResponse(retrofit2.Call<ApiResponse<PlayLinkResponse>> call,
                                   retrofit2.Response<ApiResponse<PlayLinkResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.playLink != null) {
                    Log.d(TAG, "转码兜底成功: " + response.body().data.playLink);
                    qCallback.onPlayLinkChanged(response.body().data.playLink, "1080p", 4000000);
                } else {
                    Log.w(TAG, "转码兜底：play/play 失败");
                }
            }

            @Override
            public void onFailure(retrofit2.Call<ApiResponse<PlayLinkResponse>> call, Throwable t) {
                Log.e(TAG, "转码兜底请求失败: " + t.getMessage());
            }
        });
    }

    /** 显示画质选择弹窗（自绘卡片，不用系统 AlertDialog） */
    private void showDialog() {
        if (options == null || options.isEmpty()) {
            android.widget.Toast.makeText(activity, "暂时没有可切换的画质",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);

        for (int i = 0; i < options.size(); i++) {
            QualityOption opt = options.get(i);
            boolean current = i == selectedIndex;
            android.widget.TextView row = new android.widget.TextView(activity);
            row.setText(opt.label + (current ? "  ✓" : ""));
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            row.setTextColor(com.fntv.app.ui.Ui.isNight(activity) ? 0xFFF3F6FA : 0xFF10151C);
            if (current) row.setTypeface(null, android.graphics.Typeface.BOLD);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(com.fntv.app.R.drawable.ui_card_plain);
            int ph = com.fntv.app.ui.Ui.dp(activity, 18);
            int pv = com.fntv.app.ui.Ui.dp(activity, 14);
            row.setPadding(ph, pv, ph, pv);
            row.setFocusable(true);
            row.setFocusableInTouchMode(false);
            android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = com.fntv.app.ui.Ui.dp(activity, 8);
            row.setLayoutParams(rlp);
            com.fntv.app.ui.Ui.focusEffect(row);
            list.addView(row);
        }

        final android.app.Dialog d = com.fntv.app.ui.Ui.isNight(activity)
                ? com.fntv.app.ui.Ui.dialogDark(activity, "画质选择", list, "关闭")
                : com.fntv.app.ui.Ui.dialog(activity, "画质选择", list, null, null, "关闭");

        for (int i = 0; i < options.size() && i < list.getChildCount(); i++) {
            final int idx = i;
            final QualityOption opt = options.get(i);
            list.getChildAt(i).setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    d.dismiss();
                    if (idx == selectedIndex) return;
                    selectedIndex = idx;
                    prefs.edit().putInt("stream_quality_idx_" + qCallback.getMediaGuid(),
                            selectedIndex).apply();
                    if (opt.isOriginal) {
                        qCallback.onQualityChanged(idx);
                    } else {
                        switchToQuality(opt);
                    }
                }
            });
            if (i == selectedIndex) list.getChildAt(i).requestFocus();
        }
        if (selectedIndex < 0 && list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }
    /** 非原画：调 play/play 获取新链接后切换 */
    private void switchToQuality(QualityOption selected) {
        if (streamData == null) return;
        long startTs = qCallback.getPlaybackPosition();

        Map<String, Object> body = new HashMap<>();
        body.put("media_guid", qCallback.getMediaGuid());
        if (streamData.videoStream != null) {
            body.put("video_guid", streamData.videoStream.guid != null ? streamData.videoStream.guid : "");
            body.put("video_encoder", streamData.videoStream.codecName != null ? streamData.videoStream.codecName : "");
        }
        body.put("resolution", selected.resolution);
        body.put("bitrate", selected.bitrate);
        body.put("startTimestamp", startTs);

        if (streamData.audioStreams != null && !streamData.audioStreams.isEmpty()) {
            StreamResponse.AudioStreamInfo a = streamData.audioStreams.get(0);
            body.put("audio_encoder", a.codecName != null ? a.codecName : "");
            body.put("audio_guid", a.guid != null ? a.guid : "");
            body.put("channels", a.channels);
        }
        if (streamData.subtitleStreams != null && !streamData.subtitleStreams.isEmpty()) {
            body.put("subtitle_guid", streamData.subtitleStreams.get(0).guid != null ? streamData.subtitleStreams.get(0).guid : "");
        }

        Log.d(TAG, "play/play 请求: " + new com.google.gson.Gson().toJson(body));

        apiManager.getApi().getPlayLink(body).enqueue(new retrofit2.Callback<ApiResponse<PlayLinkResponse>>() {
            @Override
            public void onResponse(retrofit2.Call<ApiResponse<PlayLinkResponse>> call,
                                   retrofit2.Response<ApiResponse<PlayLinkResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.playLink != null
                        && !response.body().data.playLink.isEmpty()) {
                    String playLink = response.body().data.playLink;
                    Log.d(TAG, "play/play 返回 link=" + playLink);
                    qCallback.onPlayLinkChanged(playLink, selected.resolution, selected.bitrate);
                } else {
                    Log.w(TAG, "play/play 失败，使用原画重试");
                    selectedIndex = 0;
                    qCallback.onQualityChanged(0);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<PlayLinkResponse>> call, Throwable t) {
                Log.e(TAG, "play/play 请求失败: " + t.getMessage());
                selectedIndex = 0;
                qCallback.onQualityChanged(0);
            }
        });
    }
}
