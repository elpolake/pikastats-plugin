package dev.PikaStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Minimal local-only RFC 6455 server; it only sends JSON text frames to the overlay. */
final class LocalWebSocketServer implements AutoCloseable {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HTTP_HEADER_BYTES = 8_192;
    private static final int MAX_CLIENT_FRAME_BYTES = 65_536;

    private final int port;
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "PikaStats-WebSocket-Client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PikaStats-WebSocket-Broadcast");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<WebSocketClient> clients = ConcurrentHashMap.newKeySet();
    private volatile String latestSnapshot;
    private volatile Consumer<String> messageHandler = ignored -> { };
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalWebSocketServer(int port, String initialSnapshot) {
        this.port = port;
        this.latestSnapshot = initialSnapshot;
    }

    void start() throws IOException {
        if (this.running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), this.port));
        this.serverSocket = socket;
        this.running = true;
        this.acceptThread = new Thread(this::acceptClients, "PikaStats-WebSocket-Accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    synchronized void publish(String event, String snapshot) {
        if (snapshot != null) {
            this.latestSnapshot = snapshot;
        }
        try {
            this.broadcastExecutor.execute(() -> {
                for (WebSocketClient client : this.clients) {
                    if (!client.send(event)) {
                        this.clients.remove(client);
                        client.close();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // ViaProxy can be shutting down while a final player update is published.
        }
    }

    void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler == null ? ignored -> { } : handler;
    }

    private synchronized void registerClient(WebSocketClient client) {
        client.send(this.latestSnapshot);
        this.clients.add(client);
    }

    private void unregisterClient(WebSocketClient client) {
        synchronized (this) {
            this.clients.remove(client);
        }
        client.close();
    }

    private void acceptClients() {
        while (this.running) {
            try {
                Socket socket = this.serverSocket.accept();
                socket.setTcpNoDelay(true);
                this.clientExecutor.execute(() -> handleClient(socket));
            } catch (IOException error) {
                if (this.running) {
                    System.err.println("[PikaStats] WebSocket accept failed: " + error.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        WebSocketClient client = null;
        try {
            String request = readHttpHeader(socket.getInputStream());
            String key = headerValue(request, "Sec-WebSocket-Key");
            if (key == null || !request.startsWith("GET ")) {
                return;
            }

            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
            OutputStream output = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            client = new WebSocketClient(socket);
            registerClient(client);
            readClientFrames(client, socket.getInputStream());
        } catch (IOException | NoSuchAlgorithmException error) {
            // A disconnected overlay is expected and must not affect ViaProxy.
        } finally {
            if (client != null) {
                unregisterClient(client);
            } else {
                closeSocket(socket);
            }
        }
    }

    private void readClientFrames(WebSocketClient client, InputStream input) throws IOException {
        while (!client.closed) {
            int first = input.read();
            if (first < 0) {
                return;
            }
            int second = input.read();
            if (second < 0) {
                return;
            }

            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = readUnsignedShort(input);
            } else if (length == 127) {
                length = readLong(input);
            }
            if (length < 0 || length > MAX_CLIENT_FRAME_BYTES) {
                client.sendClose();
                return;
            }

            byte[] mask = masked ? readExact(input, 4) : null;
            byte[] payload = readExact(input, (int) length);
            if (mask != null) {
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index & 3];
                }
            }
            if (opcode == 0x8) {
                client.sendClose();
                return;
            } else if (opcode == 0x9) {
                client.sendFrame(0xA, payload);
            } else if (opcode == 0x1) {
                try {
                    this.messageHandler.accept(new String(payload, StandardCharsets.UTF_8));
                } catch (RuntimeException ignored) {
                    // Malformed local control messages cannot affect ViaProxy or other clients.
                }
            }
        }
    }

    private static String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < MAX_HTTP_HEADER_BYTES) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("Client closed before WebSocket handshake");
            }
            bytes.write(next);
            if ((matched == 0 && next == '\r') || (matched == 1 && next == '\n')
                    || (matched == 2 && next == '\r') || (matched == 3 && next == '\n')) {
                matched++;
                if (matched == 4) {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        throw new IOException("WebSocket handshake header is too large");
    }

    private static String headerValue(String request, String headerName) {
        for (String line : request.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(headerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        return (readRequired(input) << 8) | readRequired(input);
    }

    private static long readLong(InputStream input) throws IOException {
        long result = 0;
        for (int index = 0; index < 8; index++) {
            result = (result << 8) | readRequired(input);
        }
        return result;
    }

    private static int readRequired(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("Incomplete WebSocket frame");
        }
        return value;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = input.readNBytes(length);
        if (data.length != length) {
            throw new IOException("Incomplete WebSocket frame");
        }
        return data;
    }

    @Override
    public void close() {
        this.running = false;
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        for (WebSocketClient client : this.clients) {
            client.close();
        }
        this.clients.clear();
        this.clientExecutor.shutdownNow();
        this.broadcastExecutor.shutdownNow();
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class WebSocketClient {
        private final Socket socket;
        private final OutputStream output;
        private volatile boolean closed;

        private WebSocketClient(Socket socket) throws IOException {
            this.socket = socket;
            this.output = socket.getOutputStream();
        }

        private synchronized boolean send(String text) {
            if (this.closed) {
                return false;
            }
            return sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        private synchronized boolean sendFrame(int opcode, byte[] payload) {
            if (this.closed) {
                return false;
            }
            try {
                this.output.write(0x80 | opcode);
                if (payload.length <= 125) {
                    this.output.write(payload.length);
                } else if (payload.length <= 65_535) {
                    this.output.write(126);
                    this.output.write(payload.length >>> 8);
                    this.output.write(payload.length);
                } else {
                    this.output.write(127);
                    long payloadLength = payload.length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        this.output.write((int) ((payloadLength >>> shift) & 0xFF));
                    }
                }
                this.output.write(payload);
                this.output.flush();
                return true;
            } catch (IOException error) {
                this.closed = true;
                closeSocket(this.socket);
                return false;
            }
        }

        private void sendClose() {
            sendFrame(0x8, new byte[0]);
        }

        private synchronized void close() {
            if (!this.closed) {
                this.closed = true;
                closeSocket(this.socket);
            }
        }
    }
}
