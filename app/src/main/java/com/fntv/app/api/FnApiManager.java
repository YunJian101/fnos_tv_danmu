package com.fntv.app.api;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.HostnameVerifier;

public class FnApiManager {
    private static FnApiManager instance;
    private FnApiService apiService;
    private final AuthInterceptor authInterceptor;
    private OkHttpClient okHttpClient; // 供 API 和图片使用
    private OkHttpClient streamClient; // 专供视频流使用（独立连接池）
    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;

    /** 接口内存缓存：同一个请求只发一次，一直到 App 退出（或主动失效） */
    private static final java.util.Map<String, String> RESP_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void invalidateApi(String contains) {
        if (contains == null) return;
        RESP_CACHE.keySet().removeIf(k -> k.contains(contains));
    }

    public static void clearApiCache() {
        RESP_CACHE.clear();
    }

    private static final okhttp3.Interceptor CACHE_INTERCEPTOR = new okhttp3.Interceptor() {
        @Override
        public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
            okhttp3.Request req = chain.request();
            String body = "";
            try {
                if (req.body() != null) {
                    okio.Buffer buffer = new okio.Buffer();
                    req.body().writeTo(buffer);
                    body = buffer.readUtf8();
                }
            } catch (Exception ignored) {}
            // Range 请求（视频分段）与登录请求一律不缓存
            if (req.header("Range") != null) return chain.proceed(req);
            boolean skipCache = req.url().encodedPath().contains("/login")
                    || req.url().encodedPath().contains("/logout");
            String key = req.method() + " " + req.url().toString() + " " + body;
            if (skipCache) return chain.proceed(req);
            String cached = RESP_CACHE.get(key);
            if (cached != null) {
                return new okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(okhttp3.ResponseBody.create(
                                okhttp3.MediaType.parse("application/json; charset=utf-8"), cached))
                        .build();
            }
            okhttp3.Response resp = chain.proceed(req);
            if (!resp.isSuccessful() || resp.body() == null) return resp;
            // 只缓存 JSON；图片、视频等二进制一律原样透传，不能被转成字符串
            okhttp3.MediaType mt = resp.body().contentType();
            if (mt == null || !mt.toString().toLowerCase().contains("json")) return resp;
            String text = resp.body().string();
            if (text != null && !text.isEmpty() && RESP_CACHE.size() < 400) {
                RESP_CACHE.put(key, text);
            }
            return resp.newBuilder()
                    .body(okhttp3.ResponseBody.create(mt, text))
                    .build();
        }
    };

    private FnApiManager() {
        authInterceptor = new AuthInterceptor();
    }

    public static synchronized FnApiManager getInstance() {
        if (instance == null) instance = new FnApiManager();
        return instance;
    }

    private OkHttpClient buildClient(boolean streaming) {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .hostnameVerifier(TRUST_ALL_HOSTS);
            // 只有 API/图片走缓存；视频流（Range 请求）一律直连，不缓存
            if (!streaming) builder.addInterceptor(CACHE_INTERCEPTOR);
            if (streaming) {
                // 视频流：不设超时，独立连接池
                builder.connectTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 30, java.util.concurrent.TimeUnit.SECONDS));
            } else {
                // API/图片：标准超时
                builder.connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .connectionPool(new okhttp3.ConnectionPool(2, 10, java.util.concurrent.TimeUnit.SECONDS));
            }
            return builder.build();
        } catch (Exception e) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .hostnameVerifier(TRUST_ALL_HOSTS);
            if (!streaming) builder.addInterceptor(CACHE_INTERCEPTOR);
            if (streaming) {
                builder.connectTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            try {
                X509TrustManager fallbackTrust = new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{fallbackTrust}, new java.security.SecureRandom());
                builder.sslSocketFactory(sc.getSocketFactory(), fallbackTrust);
            } catch (Exception ignored) {}
            return builder.build();
        }
    }

    public void updateBaseUrl(String baseUrl) {
        String effectiveBaseUrl = baseUrl;
        if (effectiveBaseUrl.endsWith("/")) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, effectiveBaseUrl.length() - 1);
        }
        int vIndex = effectiveBaseUrl.indexOf("/v");
        if (vIndex != -1) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, vIndex);
        }

        okHttpClient = buildClient(false);
        streamClient = buildClient(true);

        retrofit2.Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(effectiveBaseUrl + "/v/")
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build();
        apiService = retrofit.create(FnApiService.class);
    }

    public void setToken(String token) { authInterceptor.setToken(token); }
    public String getToken() { return authInterceptor.getToken(); }
    public void setReferer(String referer) { authInterceptor.setReferer(referer); }
    public FnApiService getApi() { return apiService; }

    /** 获取 OkHttpClient（API/图片） */
    public OkHttpClient getClient() { return okHttpClient; }

    /** 获取视频流专用 OkHttpClient（无超时，独立连接池） */
    public OkHttpClient getStreamClient() { return streamClient; }
}
