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

                    // ✅ 1. Broadcast pipeline events as-is
                    if ("pipeline".equals(kind)) {
                        broadcast(json.toString());
                    }

                    // 🧰 2. Handle job events and wrap them as pipeline events
                    else if ("job".equals(kind)) {
                        JsonObject build = json.getAsJsonObject("build");
                        if (build == null) return;

                        JsonObject buildPipeline = build.getAsJsonObject("pipeline");
                        if (buildPipeline == null) return;

                        // ⏳ Extract timestamps from the job object
                        String finishedAt = build.has("finished_at") && !build.get("finished_at").isJsonNull()
                                ? build.get("finished_at").getAsString()
                                : null;
                        String startedAt = build.has("started_at") && !build.get("started_at").isJsonNull()
                                ? build.get("started_at").getAsString()
                                : null;

                        String updatedAt = finishedAt != null ? finishedAt :
                                           startedAt != null ? startedAt :
                                           java.time.Instant.now().toString();

                        // 🧭 Ensure the timestamp ends with Z (UTC)
                        if (updatedAt != null && !updatedAt.endsWith("Z") && !updatedAt.contains("+")) {
                            updatedAt = updatedAt + "Z";
                        }

                        // 🧱 Construct GUI-compatible object_attributes
                        JsonObject attrs = new JsonObject();
                        attrs.addProperty("id", buildPipeline.get("id").getAsInt());
                        attrs.addProperty("status", buildPipeline.get("status").getAsString());
                        attrs.addProperty("ref", buildPipeline.get("ref").getAsString());
                        attrs.addProperty("updated_at", updatedAt);

                        JsonObject wrapped = new JsonObject();
                        wrapped.addProperty("object_kind", "pipeline");
                        wrapped.add("object_attributes", attrs);

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
