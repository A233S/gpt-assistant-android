package com.skythinker.gptassistant;

import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.unfbx.chatgpt.OpenAiStreamClient;
import com.unfbx.chatgpt.entity.chat.Message;
import com.unfbx.chatgpt.entity.chat.ChatCompletion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.hutool.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

public class ChatApiClient {
    public interface OnReceiveListener {
        void onReceive(String message);
        void onError(String message);
        void onFinished();
    }

    public enum ChatRole {
        SYSTEM,
        USER,
        ASSISTANT
    }

    String url = "";
    String apiKey = "";
    String model = "";
    OnReceiveListener listener = null;

    OkHttpClient httpClient = null;
    OpenAiStreamClient chatGPT = null;

    public ChatApiClient(String url, String apiKey, String model, OnReceiveListener listener) {
        this.listener = listener;
        this.model = model;
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        setApiInfo(url, apiKey);
    }

    public void sendPrompt(String systemPrompt, String userPrompt) {
        if(systemPrompt == null && userPrompt == null) {
            listener.onError("模板和问题内容均为空");
            return;
        }

        sendPromptList(Arrays.asList(
            Pair.create(ChatRole.SYSTEM, systemPrompt),
            Pair.create(ChatRole.USER, userPrompt)
        ));
    }

    public void sendPromptList(List<Pair<ChatRole, String>> promptList) {
        if(url.isEmpty()) {
            listener.onError("请在设置中填写服务器地址");
            return;
        } else if(apiKey.isEmpty()) {
            listener.onError("请在设置中填写ApiKey");
            return;
        } else if(chatGPT == null) {
            listener.onError("ChatGPT初始化失败");
            return;
        }

        ArrayList<Message> messages = new ArrayList<>();
        for(Pair<ChatRole, String> prompt : promptList) {
            if(prompt.first == ChatRole.SYSTEM) {
                messages.add(Message.builder().role(Message.Role.SYSTEM).content(prompt.second).build());
            } else if(prompt.first == ChatRole.USER) {
                messages.add(Message.builder().role(Message.Role.USER).content(prompt.second).build());
            } else if(prompt.first == ChatRole.ASSISTANT) {
                messages.add(Message.builder().role(Message.Role.ASSISTANT).content(prompt.second).build());
            }
        }

        ChatCompletion chatCompletion = ChatCompletion.builder()
                .messages(messages)
                .model(model)
                .build();

        chatGPT.streamChatCompletion(chatCompletion, new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                Log.d("ChatApiClient", "onOpen");
            }

            @Override
            public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
                if(data.equals("[DONE]")){
                    Log.d("ChatApiClient", "onEvent: DONE");
                    listener.onFinished();
                } else {
                    String msg = ((JSONObject) (new JSONObject(data)).getJSONArray("choices").get(0)).getJSONObject("delta").getStr("content");
                    if(msg != null)
                        listener.onReceive(msg);
//                    Log.d("ChatApiClient", "onEvent: " + data);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                Log.d("ChatApiClient", "onClosed");
            }

            @Override
            public void onFailure(EventSource eventSource, @Nullable Throwable throwable, @Nullable Response response) {
                if(throwable != null) {
                    if(throwable instanceof StreamResetException) {
                        Log.d("ChatApiClient", "onFailure: Cancelled");
                        listener.onFinished();
                    } else {
                        String err = throwable.toString();
                        listener.onError(err);
                    }
                } else {
                    if(response != null && response.body() != null) {
                        try {
                            String err = response.body().string();
                            if(err.length() > 300) {
                                err = err.substring(0, 300);
                                err += "...";
                            }
                            listener.onError(err);
                        } catch (IOException ignore) { }
                    } else {
                        listener.onError("未知错误");
                    }
                }
            }
        });
    }

    public void setApiInfo(String url, String apiKey) {
        if(this.url.equals(url) && this.apiKey.equals(apiKey)) {
            return;
        }
        this.url = url;
        this.apiKey = apiKey;
        chatGPT = new OpenAiStreamClient.Builder()
            .apiKey(Arrays.asList(apiKey))
            .apiHost(url)
            .okHttpClient(httpClient)
            .build();
    }

    public boolean isStreaming() {
        return httpClient.connectionPool().connectionCount() - httpClient.connectionPool().idleConnectionCount() > 0;
    }

    public void stop() {
        httpClient.dispatcher().cancelAll();
    }

    public void setModel(String model) {
        this.model = model;
    }
}
