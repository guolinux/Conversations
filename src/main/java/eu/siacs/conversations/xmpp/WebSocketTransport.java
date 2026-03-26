package eu.siacs.conversations.xmpp;

import android.util.Log;
import eu.siacs.conversations.Config;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Bridges an OkHttp WebSocket to InputStream/OutputStream so the existing XmlReader and TagWriter
 * can work over WebSocket with minimal changes. Each WebSocket text message received is written into
 * a PipedOutputStream that feeds a PipedInputStream consumed by XmlReader. The OutputStream
 * provided to TagWriter captures each flush as a discrete WebSocket text message.
 */
public class WebSocketTransport {

    private final WebSocket webSocket;
    private final PipedInputStream readerInputStream;
    private final PipedOutputStream readerFeeder;
    private final WebSocketOutputStream writerOutputStream;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private volatile boolean connected = false;
    private volatile String failureMessage = null;
    private final boolean secure;

    private WebSocketTransport(
            final WebSocket webSocket,
            final PipedInputStream readerInputStream,
            final PipedOutputStream readerFeeder,
            final WebSocketOutputStream writerOutputStream,
            final boolean secure) {
        this.webSocket = webSocket;
        this.readerInputStream = readerInputStream;
        this.readerFeeder = readerFeeder;
        this.writerOutputStream = writerOutputStream;
        this.secure = secure;
    }

    /**
     * Connects to the given WebSocket URL and returns a WebSocketTransport. Blocks until the
     * WebSocket handshake completes or times out.
     */
    public static WebSocketTransport connect(
            final OkHttpClient client, final String url, final int timeoutSeconds)
            throws IOException {
        final boolean secure = url.startsWith("wss://");
        final PipedOutputStream readerFeeder = new PipedOutputStream();
        final PipedInputStream readerInputStream = new PipedInputStream(readerFeeder, 64 * 1024);
        final WebSocketOutputStream writerOutputStream = new WebSocketOutputStream();

        final WebSocketTransport[] transportHolder = new WebSocketTransport[1];

        final Request request =
                new Request.Builder().url(url).addHeader("Sec-WebSocket-Protocol", "xmpp").build();

        final WebSocketListener listener =
                new WebSocketListener() {
                    @Override
                    public void onOpen(@NonNull final WebSocket ws, @NonNull final Response r) {
                        final String protocol = r.header("Sec-WebSocket-Protocol");
                        if (!"xmpp".equals(protocol)) {
                            Log.w(
                                    Config.LOGTAG,
                                    "WebSocket server did not confirm xmpp subprotocol: "
                                            + protocol);
                        }
                        final WebSocketTransport transport = transportHolder[0];
                        if (transport != null) {
                            transport.writerOutputStream.setWebSocket(ws);
                            transport.connected = true;
                            transport.openLatch.countDown();
                        }
                    }

                    @Override
                    public void onMessage(@NonNull final WebSocket ws, @NonNull final String text) {
                        final WebSocketTransport transport = transportHolder[0];
                        if (transport == null || transport.closed.get()) {
                            return;
                        }
                        try {
                            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                            transport.readerFeeder.write(bytes);
                            transport.readerFeeder.flush();
                        } catch (final IOException e) {
                            Log.d(Config.LOGTAG, "error feeding WebSocket message to reader", e);
                            transport.close();
                        }
                    }

                    @Override
                    public void onFailure(
                            @NonNull final WebSocket ws,
                            @NonNull final Throwable t,
                            @Nullable final Response r) {
                        Log.d(Config.LOGTAG, "WebSocket failure: " + t.getMessage());
                        final WebSocketTransport transport = transportHolder[0];
                        if (transport != null) {
                            transport.failureMessage = t.getMessage();
                            transport.openLatch.countDown();
                            transport.close();
                        }
                    }

                    @Override
                    public void onClosing(
                            @NonNull final WebSocket ws, final int code, @NonNull final String r) {
                        ws.close(code, r);
                        final WebSocketTransport transport = transportHolder[0];
                        if (transport != null) {
                            transport.close();
                        }
                    }

                    @Override
                    public void onClosed(
                            @NonNull final WebSocket ws, final int code, @NonNull final String r) {
                        final WebSocketTransport transport = transportHolder[0];
                        if (transport != null) {
                            transport.close();
                        }
                    }
                };

        final WebSocket ws = client.newWebSocket(request, listener);
        final WebSocketTransport transport =
                new WebSocketTransport(ws, readerInputStream, readerFeeder, writerOutputStream, secure);
        transportHolder[0] = transport;
        writerOutputStream.setWebSocket(ws);

        try {
            if (!transport.openLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                ws.cancel();
                throw new IOException("WebSocket connection timed out");
            }
        } catch (final InterruptedException e) {
            ws.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("WebSocket connection interrupted", e);
        }

        if (!transport.connected) {
            final String msg =
                    transport.failureMessage != null
                            ? transport.failureMessage
                            : "WebSocket connection failed";
            throw new IOException(msg);
        }

        return transport;
    }

    public InputStream getInputStream() {
        return readerInputStream;
    }

    public OutputStream getOutputStream() {
        return writerOutputStream;
    }

    public boolean isSecure() {
        return secure;
    }

    public boolean isConnected() {
        return connected && !closed.get();
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            connected = false;
            try {
                readerFeeder.close();
            } catch (final IOException ignored) {
            }
            try {
                readerInputStream.close();
            } catch (final IOException ignored) {
            }
            try {
                webSocket.close(1000, "stream closed");
            } catch (final Exception ignored) {
            }
        }
    }

    /**
     * An OutputStream that captures all written bytes and sends them as a single WebSocket text
     * message on each {@code sendMessage()} call. Regular {@code flush()} is a no-op to prevent
     * premature sends from BufferedWriter/OutputStreamWriter internal flushing.
     */
    public static class WebSocketOutputStream extends OutputStream {
        private volatile WebSocket webSocket;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        void setWebSocket(final WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        @Override
        public synchronized void write(final int b) throws IOException {
            buffer.write(b);
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len)
                throws IOException {
            buffer.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            // No-op: prevent BufferedWriter/OutputStreamWriter internal flushes from
            // triggering premature WebSocket message sends. Use sendMessage() instead.
        }

        public synchronized void sendMessage() throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            final String message = buffer.toString("UTF-8");
            buffer.reset();
            final WebSocket ws = this.webSocket;
            if (ws == null) {
                throw new IOException("WebSocket not connected");
            }
            if (!ws.send(message)) {
                throw new IOException("WebSocket send failed");
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
