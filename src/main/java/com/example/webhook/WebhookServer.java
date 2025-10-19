package com.example.webhook;

import io.undertow.Undertow;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebhookServer {

    private static final Set<WebSocketChannel> clients = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        // 👇 Define WebSocket handler
        WebSocketProtocolHandshakeHandler wsHandler =
            Handlers.websocket(new WebSocketConnectionCallback() {
                @Override
                public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
                    clients.add(channel);
                    System.out.println("🔌 WebSocket client connected: " + channel.getPeerAddress());

                    channel.getReceiveSetter().set(new AbstractReceiveListener() {
                        @Override
                        protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
                            String msg = message.getData();
                            System.out.println("📩 Message from GUI: " + msg);
                        }

                        @Override
                        protected void onClose(WebSocketChannel channel, StreamSourceFrameChannel frameChannel) {
                            clients.remove(channel);
                            System.out.println("❌ WebSocket client disconnected: " + channel.getPeerAddress());
                        }
                    });
                    channel.resumeReceives();
                }
            });

        // 👇 Basic /ping endpoint
        HttpHandler pingHandler = exchange -> {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("OK");
        };

        // 👇 Webhook endpoint
        HttpHandler webhookHandler = exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
                exchange.setStatusCode(405);
                exchange.endExchange();
                return;
            }

            exchange.getRequestReceiver().receiveFullString((ex, data) -> {
                System.out.println("📥 Received webhook payload:\n" + data);

                try {
                    JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                    String kind = json.get("object_kind").getAsString();

                    if ("pipeline".equals(kind)) {
                        // ✅ Broadcast pipeline events as-is
                        broadcast(json.toString());
                    }
                    else if ("job".equals(kind)) {
                        // ✅ Extract pipeline and merge finished_at timestamp
                        JsonObject pipeline = json.getAsJsonObject("pipeline");
                        String finishedAt = json.has("finished_at") ? json.get("finished_at").getAsString() : null;
                        String startedAt = json.has("started_at") ? json.get("started_at").getAsString() : null;

                        if (pipeline != null) {
                            if (!pipeline.has("updated_at")) {
                                if (finishedAt != null) {
                                    pipeline.addProperty("updated_at", finishedAt);
                                } else if (startedAt != null) {
                                    pipeline.addProperty("updated_at", startedAt);
                                }
                            }

                            JsonObject wrapped = new JsonObject();
                            wrapped.addProperty("object_kind", "pipeline");
                            wrapped.add("object_attributes", pipeline);

                            broadcast(wrapped.toString());
                        }
                    }
                } catch (Exception err) {
                    System.err.println("⚠️ Failed to parse or handle webhook: " + err.getMessage());
                }

                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                ex.getResponseSender().send("OK");
            });
        };

        // 👇 Build server
        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(Handlers.path()
                        .addPrefixPath("/ping", pingHandler)
                        .addPrefixPath("/webhook", webhookHandler)
                        .addPrefixPath("/ws", wsHandler) // 👈 WebSocket lives here
                )
                .build();

        server.start();
        System.out.println("✅ Undertow server running on port " + port);
    }

    private static void broadcast(String message) {
        for (WebSocketChannel client : clients) {
            WebSockets.sendText(message, client, null);
        }
        System.out.println("📡 Broadcasted to " + clients.size() + " clients");
    }
}
