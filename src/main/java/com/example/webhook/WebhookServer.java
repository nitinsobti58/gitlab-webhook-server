package com.example.webhook;

import io.undertow.Undertow;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebhookServer {

    //set of all websocket connections
    //When GUI connects to websocjet its channel is added to this set
    private static final Set<WebSocketChannel> clients = ConcurrentHashMap.newKeySet();

    //Stores the discord webhook urld
    //Reads the variable from render environment variable
    private static final String DISCORD_WEBHOOK_URL =System.getenv("DISCORD_WEBHOOK_URL");

    //Gitlab timestamps come as "2025-10-19 21:55:41 UTC" and need to be converted to ISO 8601
    private static String extractTimestamp(JsonObject obj) {
        String timestamp = null;
        
        //retrieve either finished at or created at timestamp
        if (obj.has("finished_at") && !obj.get("finished_at").isJsonNull()) {
            timestamp = obj.get("finished_at").getAsString();
        } else if (obj.has("created_at") && !obj.get("created_at").isJsonNull()) {
            timestamp = obj.get("created_at").getAsString();
        }

        if (timestamp != null) {
            // Convert "2025-10-19 21:55:41 UTC" to "2025-10-19T21:55:41Z"
            timestamp = timestamp.replace(" UTC", "Z").replace(" ", "T");
        }
        return timestamp;
    }

    //Sends a message to discord
    private static void sendDiscord(String discordMessage) {
            
            System.out.println("Sending discord message: " + discordMessage+"\n");
            //create discord POST Request
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_WEBHOOK_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(discordMessage))
                .build();

            //sends request
            HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    //Sends a message to all connected clients
    //Only connected Channels Should be GUI
    private static void broadcast(String message) {
        
        //For each GUI, send the message
        for (WebSocketChannel client : clients) {
            WebSockets.sendText(message, client, null);
        }
        System.out.println("Sent Message to " + clients.size() + " GUI(s)"+"\n");
    }
    
    public static void main(String[] args) {

        //Reads the port from render environment variable
        int port = Integer.parseInt(System.getenv("PORT"));

        // WebSocket handler
        //creates a websocket endpoint 
        WebSocketProtocolHandshakeHandler wsHandler =
            Handlers.websocket((exchange, channel) -> {
                
                //when a GUI connects, add it to the set
                clients.add(channel);

                channel.getReceiveSetter().set(new AbstractReceiveListener() {
                    protected void onClose(WebSocketChannel ch, StreamSourceFrameChannel msg) {
                        //when a GUI disconnects, remove it from the set
                        clients.remove(ch);
                    }
                });

                //activate websocket so it can receive events 
                channel.resumeReceives();
            });


        //Undertow interface to handle HTTP request and genereta a response
        //Uptime Robot pings this endpoint to check if the server is up
        HttpHandler pingHandler = exchange -> {
            System.out.println("Uptime Robot pinged"+"\n");
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("OK");
        };

        // Webhook endpoint
        HttpHandler webhookHandler = exchange -> {
            //ignore non Post Requests ensure that only POST requests are processed
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
                exchange.setStatusCode(405);
                exchange.endExchange();
                return;
            }

            //Read the whole webhook JSON body as text
            //data is full json payload as a string
            exchange.getRequestReceiver().receiveFullString((ex, data) -> 
            {
                System.out.println("Received webhook payload:\n" + data+"\n");

                try
                {
                    JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                    String kind = json.get("object_kind").getAsString();

                    // Handle pipeline events
                    if ("pipeline".equals(kind)) {
                        JsonObject attrs = json.getAsJsonObject("object_attributes");

                       
                        String updatedAt = extractTimestamp(attrs);
                        if (updatedAt != null) {
                            attrs.addProperty("updated_at", updatedAt);
                        }

                        //create a new json object to wrap the pipeline attributes
                        JsonObject guiMessaage = new JsonObject();
                        guiMessaage.addProperty("object_kind", "pipeline");
                        guiMessaage.add("object_attributes", attrs);

                        //send the wrapped json object to GUI
                        broadcast(guiMessaage.toString());
                        
                        // Create Discord message as JSON object
                        JsonObject discordMessage = new JsonObject();
                        
                        String discordString = "Pipeline #: " + attrs.get("id").getAsLong() + "\n" + "Branch: " + attrs.get("ref").getAsString() + "\n" + "Status: " 
                            + attrs.get("status").getAsString() + "\n" + "Triggered at: " + updatedAt + "\n"
                            + "Triggered Source: " + attrs.get("source").getAsString()+"\n"+"\n";
                       
                        discordMessage.addProperty("content", discordString);
                        

                        //send the discord message
                        sendDiscord(discordMessage.toString());
                    }

                }
                catch (Exception ignored) {
                    System.out.println("Failed to process webhook payload");
                }
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                ex.getResponseSender().send("OK");
            });
        };

        //Create Undertow server
        Undertow server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(Handlers.path()
                        .addPrefixPath("/ping", pingHandler)
                        .addPrefixPath("/webhook", webhookHandler)
                        .addPrefixPath("/ws", wsHandler))
                .build();

        //Start server
        server.start();
        
    }
}
