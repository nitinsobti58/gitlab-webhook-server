package com.example.webhook;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        int wsPort = port;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/webhook", new WebhookHandler());
        server.createContext("/ping", new PingHandler()); // for uptime pings
        server.setExecutor(null);
        server.start();
        System.out.println("✅ Webhook server running on port " + port);

        EventSocketServer wsServer = new EventSocketServer(wsPort);
        wsServer.start();
    }

    // Lightweight liveness endpoint
static class PingHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);

        if ("HEAD".equalsIgnoreCase(method)) {
            // ✅ Correct: no body for HEAD
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }

        if (!"GET".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }

        // Normal GET response
        ex.sendResponseHeaders(200, ok.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(ok);
        }
    }
}


static class WebhookHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Token check (unchanged)
        String provided = exchange.getRequestHeaders().getFirst("X-Gitlab-Token");
        String expected = System.getenv("GITLAB_WEBHOOK_SECRET");
        if (expected != null && (provided == null || !expected.equals(provided))) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("📥 Raw GitLab webhook payload received");

        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject attrs = root.getAsJsonObject("object_attributes");

            String projectName = root.getAsJsonObject("project").get("name").getAsString();
            String branch = attrs.get("ref").getAsString();
            String status = attrs.get("status").getAsString();
            long pipelineId = attrs.get("id").getAsLong();
            String triggeredBy = root.getAsJsonObject("user").get("username").getAsString();
            String commitMessage = root.has("commit") 
                    ? root.getAsJsonObject("commit").get("title").getAsString()
                    : "";

            PipelineEvent event = new PipelineEvent(projectName, branch, status, pipelineId, triggeredBy, commitMessage);
            String json = new Gson().toJson(event);

            System.out.println("📡 Broadcasting structured event: " + json);
            EventSocketServer.broadcastMessage(json);

        } catch (Exception e) {
            System.err.println("⚠️ Failed to parse webhook: " + e.getMessage());
            e.printStackTrace();
            EventSocketServer.broadcastMessage(body); // fallback: send raw if parse fails
        }

        byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, ok.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
    }
}



}
