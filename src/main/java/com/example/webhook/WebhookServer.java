package com.example.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class WebhookServer {
    public static void main(String[] args) throws IOException {
        // Render sets PORT env var for you
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/webhook", new WebhookHandler());
        server.createContext("/ping", new PingHandler()); // for uptime pings
        server.setExecutor(null);
        server.start();
        System.out.println("✅ Webhook server running on port " + port);
    }

    // Lightweight liveness endpoint
    static class PingHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(ok); }
        }
    }

    static class WebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Optional: verify X-Gitlab-Token for security (set in Render env)
            String provided = exchange.getRequestHeaders().getFirst("X-Gitlab-Token");
            String expected = System.getenv("GITLAB_WEBHOOK_SECRET"); // set on Render
            if (expected != null && (provided == null || !expected.equals(provided))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("📥 Received GitLab webhook payload:\n" + body);

            // TODO (later): parse JSON and/or notify your GUI service
            // Easiest first step: just accept & log it.

            byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
        }
    }
}
