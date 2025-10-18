package com.example.webhook;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import java.net.InetSocketAddress;
import java.util.*;

public class EventSocketServer extends WebSocketServer {

    private static final Set<WebSocket> connections = Collections.synchronizedSet(new HashSet<>());

    public EventSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        System.out.println("🔌 New WebSocket connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        System.out.println("❌ WebSocket closed: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // If GUI ever needs to send something back
        System.out.println("📩 Message from GUI: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("✅ WebSocket server started on port " + getPort());
    }

    public static void broadcastMessage(String msg) {
            System.out.println("📡 Broadcasting to " + connections.size() + " clients: " + msg); // 👈 debug line

        synchronized (connections) {
            for (WebSocket conn : connections) {
                conn.send(msg);
            }
        }
    }
}

