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

        // 👇 WebSocket handler
        WebSocketProtocolHandshakeHandler wsHandler =
            Handlers.websocket(new WebSocketConnectionCallback() {
                @Override
                public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
                    clients.add(channel);
                    System.out.println("🔌 WebSocket client connected: " + channel.getPeerAddress());

                    channel.getReceiveSetter().set(new AbstractReceiveListener() {
                        @Override
                        protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
                            System.out.println("📩 Message from GUI: " + message.getData());
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

        // 👇 Ping endpoint
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

                    // ✅ Handle pipeline events
                    if ("pipeline".equals(kind)) {
                        JsonObject attrs = json.getAsJsonObject("object_attributes");

                        // 🕒 Derive updated_at from finished_at or created_at
                        String updatedAt = extractTimestamp(attrs);
                        if (updatedAt != null) {
                            attrs.addProperty("updated_at", updatedAt);
                        }

                        JsonObject wrapped = new JsonObject();
                        wrapped.addProperty("object_kind", "pipeline");
                        wrapped.add("object_attributes", attrs);

                        System.out.println("🛰️ FINAL OUTGOING MESSAGE:\n" + wrapped);
                        broadcast(wrapped.toString());
                    }

                    // 🧰 Handle job events
                    else if ("job".equals(kind)) {
                        JsonObject build = json.getAsJsonObject("build");
                        if (build == null) return;

                        JsonObject pipeline = build.getAsJsonObject("pipeline");
                        if (pipeline == null) return;

                        String updatedAt = extractTimestamp(build);
                        if (updatedAt != null) {
                            pipeline.addProperty("updated_at", updatedAt);
                        }

                        JsonObject wrapped = new JsonObject();
                        wrapped.addProperty("object_kind", "pipeline");
                        wrapped.add("object_attributes", pipeline);

                        System.out.println("🛰️ FINAL OUTGOING MESSAGE (job wrapped):\n" + wrapped);
                        broadcast(wrapped.toString());
                    }

                } catch (Exception err) {
                    System.err.println("⚠️ Failed to parse or handle webhook: " + err.getMessage());
                }

                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                ex.getResponseSender().send("OK");
            });
        };

        // 👇 Start server
        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(Handlers.path()
                        .addPrefixPath("/ping", pingHandler)
                        .addPrefixPath("/webhook", webhookHandler)
                        .addPrefixPath("/ws", wsHandler))
                .build();

        server.start();
        System.out.println("✅ Undertow server running on port " + port);
    }

    /**
     * Extracts a usable timestamp from finished_at or created_at and converts it to ISO 8601 with Z.
     */
    private static String extractTimestamp(JsonObject obj) {
        String timestamp = null;
        if (obj.has("finished_at") && !obj.get("finished_at").isJsonNull()) {
            timestamp = obj.get("finished_at").getAsString();
        } else if (obj.has("created_at") && !obj.get("created_at").isJsonNull()) {
            timestamp = obj.get("created_at").getAsString();
        }

        if (timestamp != null) {
            // Convert "2025-10-19 21:55:41 UTC" → "2025-10-19T21:55:41Z"
            timestamp = timestamp.replace(" UTC", "Z").replace(" ", "T");
        }
        return timestamp;
    }

    private static void broadcast(String message) {
        System.out.println("📡 Broadcasting to " + clients.size() + " clients");
        for (WebSocketChannel client : clients) {
            WebSockets.sendText(message, client, null);
        }
    }
}
