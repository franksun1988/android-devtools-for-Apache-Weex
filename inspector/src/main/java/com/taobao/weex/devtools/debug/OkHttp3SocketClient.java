package com.taobao.weex.devtools.debug;

import android.text.TextUtils;
import android.util.Log;

import com.taobao.weex.devtools.common.LogRedirector;
import com.taobao.weex.devtools.common.ReflectionUtil;

import org.apache.weex.utils.WXLogUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class OkHttp3SocketClient extends SocketClient {

    private static final String TAG = "OkHttp3SocketClient";

    private static HashMap<String, Class> sClazzMap = new HashMap<String, Class>();
    private static final String CLASS_WEBSOCKET = "okhttp3.WebSocket";
    private static final String CLASS_WEBSOCKET_LISTENER = "okhttp3.WebSocketListener";
    private static final String CLASS_WEBSOCKET_CALL = "okhttp3.ws.WebSocketCall";
    private static final String CLASS_MEDIATYPE = "okhttp3.MediaType";

    private static final String CLASS_OKHTTP_CLIENT = "okhttp3.OkHttpClient";
    private static final String CLASS_OKHTTP_CLIENT_BUILDER = "okhttp3.OkHttpClient$Builder";
    private static final String CLASS_RESPONSE = "okhttp3.Response";
    private static final String CLASS_REQUEST = "okhttp3.Request";
    private static final String CLASS_RESPONSE_BODY = "okhttp3.ResponseBody";
    private static final String CLASS_REQUEST_BODY = "okhttp3.RequestBody";
    private static final String CLASS_REQUEST_BUILDER = "okhttp3.Request$Builder";

    private static final String CLASS_BUFFER = "okio.Buffer";
    private static final String CLASS_BUFFER_SOURCE = "okio.BufferedSource";

    static {
        String[] classNames = new String[]{
                CLASS_WEBSOCKET,
                CLASS_WEBSOCKET_LISTENER,
                CLASS_WEBSOCKET_CALL,
                CLASS_MEDIATYPE,
                CLASS_OKHTTP_CLIENT,
                CLASS_OKHTTP_CLIENT_BUILDER,
                CLASS_RESPONSE,
                CLASS_REQUEST,
                CLASS_REQUEST_BUILDER,
                CLASS_BUFFER,
                CLASS_BUFFER_SOURCE,
                CLASS_REQUEST_BODY,
                CLASS_RESPONSE_BODY
        };
        for (String className : classNames) {
            sClazzMap.put(className, ReflectionUtil.tryGetClassForName(className));
        }
    }

    private Class mOkHttpClientClazz = sClazzMap.get(CLASS_OKHTTP_CLIENT);
    private Class mOkHttpClientBuilderClazz = sClazzMap.get(CLASS_OKHTTP_CLIENT_BUILDER);

    private Class mRequestClazz = sClazzMap.get(CLASS_REQUEST);
    private Class mRequestBuilderClazz = sClazzMap.get(CLASS_REQUEST_BUILDER);
    private Class mWebSocketCallClazz = sClazzMap.get(CLASS_WEBSOCKET_CALL);
    private Class mWebSocketListenerClazz = sClazzMap.get(CLASS_WEBSOCKET_LISTENER);

    private Class mRequestBodyClazz = sClazzMap.get(CLASS_REQUEST_BODY);
    private Class mResponseBodyClazz = sClazzMap.get(CLASS_RESPONSE_BODY);
    private Class mMediaTypeClazz = sClazzMap.get(CLASS_MEDIATYPE);
    private Class mWebSocketClazz = sClazzMap.get(CLASS_WEBSOCKET);
    private Class mBufferedSourceClazz = sClazzMap.get(CLASS_BUFFER_SOURCE);

    public OkHttp3SocketClient(DebugServerProxy proxy) {
        super(proxy);
        mInvocationHandler = new WebSocketInvocationHandler();
    }

    protected void connect(String url) {
        if (mSocketClient != null) {
            throw new IllegalStateException("OkHttp3SocketClient is already initialized.");
        }
        try {
            Object builder = mOkHttpClientBuilderClazz.newInstance();
            Method connectTimeout = ReflectionUtil.tryGetMethod(
                    mOkHttpClientBuilderClazz,
                    "connectTimeout",
                    new Class[]{long.class, TimeUnit.class});

            Method writeTimeout = ReflectionUtil.tryGetMethod(
                    mOkHttpClientBuilderClazz,
                    "writeTimeout",
                    new Class[]{long.class, TimeUnit.class});

            Method readTimeout = ReflectionUtil.tryGetMethod(
                    mOkHttpClientBuilderClazz,
                    "readTimeout",
                    new Class[]{long.class, TimeUnit.class});

            builder = ReflectionUtil.tryInvokeMethod(builder, connectTimeout, 30, TimeUnit.SECONDS);
            builder = ReflectionUtil.tryInvokeMethod(builder, writeTimeout, 30, TimeUnit.SECONDS);
            builder = ReflectionUtil.tryInvokeMethod(builder, readTimeout, 0, TimeUnit.SECONDS);


            Method build = ReflectionUtil.tryGetMethod(mOkHttpClientBuilderClazz, "build");

            mSocketClient = ReflectionUtil.tryInvokeMethod(builder, build);

            if (!TextUtils.isEmpty(url)) {
                Object requestBuilder = mRequestBuilderClazz.newInstance();
                Method urlMethod = ReflectionUtil.tryGetMethod(
                        mRequestBuilderClazz,
                        "url",
                        new Class[]{String.class});
                Method buildMethod = ReflectionUtil.tryGetMethod(
                        mRequestBuilderClazz,
                        "build");

                requestBuilder = ReflectionUtil.tryInvokeMethod(requestBuilder, urlMethod, url);
                Object request = ReflectionUtil.tryInvokeMethod(requestBuilder, buildMethod);

                Method newWebSocketMethod = ReflectionUtil.tryGetMethod(
                        mOkHttpClientClazz,
                        "newWebSocket",
                        new Class[]{mRequestClazz, mWebSocketListenerClazz});

//                mWebSocketListener = Proxy.newProxyInstance(
//                        mWebSocketListenerClazz.getClassLoader(),
//                        new Class[]{mWebSocketListenerClazz},
//                        mInvocationHandler);
                 mWebSocketListener = new WebSocketListener() {
                     @Override
                     public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                         if (mHandlerThread != null && mHandlerThread.isAlive()) {
                             mHandler.sendEmptyMessage(CLOSE_WEB_SOCKET);
                         }
                     }

                     @Override
                     public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                         abort("Websocket onFailure", t);
                     }

                     @Override
                     public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                         try {
                             mProxy.handleMessage(text);
                         } catch (Exception e) {
                             if (LogRedirector.isLoggable(TAG, Log.VERBOSE)) {
                                 LogRedirector.v(TAG, "Unexpected I/O exception processing message: " + e);
                             }
                         }
                     }

                     @Override
                     public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                         mWebSocket = webSocket;
                         if (mConnectCallback != null) {
                             mConnectCallback.onSuccess(null);
                         }
                     }
                 };
                ReflectionUtil.tryInvokeMethod(mSocketClient, newWebSocketMethod, request, mWebSocketListener);
            }
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    protected void close() {
        if (mWebSocket != null) {
            Method closeMethod = ReflectionUtil.tryGetMethod(
                    mWebSocketClazz,
                    "close",
                    new Class[]{int.class, String.class});

            ReflectionUtil.tryInvokeMethod(mWebSocket, closeMethod, 1000, "End of session");
            mWebSocket = null;
            WXLogUtils.w(TAG, "Close websocket connection");
        }
    }

    @Override
    protected void sendProtocolMessage(int requestID, String message) {
        if (mWebSocket == null) {
            return;
        }

        Method sendMessageMethod = ReflectionUtil.tryGetMethod(
                mWebSocketClazz,
                "send",
                new Class[]{String.class});

        ReflectionUtil.tryInvokeMethod(mWebSocket, sendMessageMethod, message);
    }


    private void abort(String message, Throwable cause) {
        Log.w(TAG, "Error occurred, shutting down websocket connection: " + message);
        close();

        // Trigger failure callbacks
        if (mConnectCallback != null) {
            mConnectCallback.onFailure(cause);
            mConnectCallback = null;
        }
    }

    class WebSocketInvocationHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            if ("onOpen".equals(method.getName())) {
                mWebSocket = mWebSocketClazz.cast(args[0]);
                if (mConnectCallback != null) {
                    mConnectCallback.onSuccess(null);
                }
            } else if ("onFailure".equals(method.getName())) {
                abort("Websocket onFailure", (Throwable) args[1]);
            } else if ("onMessage".equals(method.getName())) {
                try {
                    String message = (String) args[1];
                    mProxy.handleMessage(message);
                } catch (Exception e) {
                    if (LogRedirector.isLoggable(TAG, Log.VERBOSE)) {
                        LogRedirector.v(TAG, "Unexpected I/O exception processing message: " + e);
                    }
                }
            } else if ("onClosed".equals(method.getName())) {
                if (mHandlerThread != null && mHandlerThread.isAlive()) {
                    mHandler.sendEmptyMessage(CLOSE_WEB_SOCKET);
                }
            }
            return null;
        }
    }
}
