package com.fntv.app.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.fntv.app.api.model.ApiResponse;

/**
 * 进程内接口缓存：页面来回切不再重复请求，数据一直留到 App 退出。
 * 需要刷新时用 invalidate() 主动失效（例如从播放器返回后刷新播放进度）。
 */
public final class ApiCache {

    private ApiCache() {}

    private static final Map<String, Object> MAP = new ConcurrentHashMap<>();

    public static Object get(String key) {
        return MAP.get(key);
    }

    public static void put(String key, Object value) {
        if (key != null && value != null) MAP.put(key, value);
    }

    public static void invalidate(String key) {
        if (key != null) MAP.remove(key);
    }

    public static void clear() {
        MAP.clear();
    }

    /** 带缓存的数据回调 */
    public interface Sink<T> {
        void onData(T data);
    }

    /**
     * 命中缓存直接走回调（同步、不闪），没有再发请求并写入缓存。
     * 失败时回调 null，交给调用方决定是否忽略。
     */
    @SuppressWarnings("unchecked")
    public static <T> void load(final String key, Call<ApiResponse<T>> call, final Sink<T> sink) {
        Object hit = MAP.get(key);
        if (hit instanceof ApiResponse) {
            ApiResponse<T> cached = (ApiResponse<T>) hit;
            if (cached.code == 0 && cached.data != null) {
                sink.onData(cached.data);
                return;
            }
        }
        call.enqueue(new Callback<ApiResponse<T>>() {
            @Override
            public void onResponse(Call<ApiResponse<T>> c, Response<ApiResponse<T>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
                    put(key, response.body());
                    sink.onData(response.body().data);
                } else {
                    sink.onData(null);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<T>> c, Throwable t) {
                sink.onData(null);
            }
        });
    }
}
