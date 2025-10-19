package com.example.webhook;

import io.undertow.Undertow;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

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
                        // 🛠️ Correct handling of job events
                        JsonObject build = json.getAsJsonObject("build");
                        if (build == null) return;

                        JsonObject pipeline = build.getAsJsonObject("pipeline");
                        if (pipeline == null) return;

                        String finishedAt = build.has("finished_at") && !build.get("finished_at").isJsonNull()
                                ? build.get("finished_at").getAsString()
                                : null;
                        String startedAt = build.has("started_at") && !build.get("started_at").isJsonNull()
                                ? build.get("started_at").getAsString()
                                : null;

                        if (!pipeline.has("updated_at")) {
                            if (finishedAt != null) {
                                pipeline.addProperty("updated_at", finishedAt);
                            } else if (startedAt != null) {
                                pipeline.addProperty("updated_at", startedAt);
                            } else {
                                // Optional fallback to now if neither is set
                                pipeline.addProperty("updated_at", java.time.Instant.now().toString());
                            }
                        }

                        JsonObject wrapped = new JsonObject();
                        wrapped.addProperty("object_kind", "pipeline");
                        wrapped.add("object_attributes", pipeline);

                        System.out.println("📡 Sending wrapped job as pipeline event:\n" + wrapped);
                        broadcast(wrapped.toString());
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
