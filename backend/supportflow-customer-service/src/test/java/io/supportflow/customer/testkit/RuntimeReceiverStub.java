package io.supportflow.customer.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback-only HTTP faults. Contains synthetic test input only. */
public final class RuntimeReceiverStub implements AutoCloseable {
    public record Request(String method, String rawPath, String rawQuery, Map<String, List<String>> headers,
                          byte[] body) { }
    public record Reply(int status, String body, String type, long bodyDelayMillis, String location) {
        public Reply(int status, String body) { this(status, body, "application/json", 0, null); }
    }
    @FunctionalInterface public interface Handler { Reply apply(Request request) throws Exception; }
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RuntimeReceiverStub(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    public URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/ai/v1/runtime/runs"); }

    private void handle(HttpExchange exchange, Handler handler) {
        try (exchange) {
            calls.incrementAndGet();
            Reply reply = handler.apply(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(),
                    exchange.getRequestBody().readNBytes(8193)));
            exchange.getResponseHeaders().set("Content-Type", reply.type());
            if (reply.location() != null) exchange.getResponseHeaders().set("Location", reply.location());
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            if (reply.bodyDelayMillis() > 0) Thread.sleep(reply.bodyDelayMillis());
            exchange.getResponseBody().write(body);
        } catch (java.io.IOException expectedCancellation) {
            // A cancelled/oversize response can close the peer before this write.
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) { failure.compareAndSet(null, error); }
    }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
