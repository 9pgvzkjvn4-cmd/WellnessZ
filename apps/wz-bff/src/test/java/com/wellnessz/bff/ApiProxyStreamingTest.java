package com.wellnessz.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * The proxy relays streaming responses unbuffered (contract §18, E33 capture stream): every SSE chunk reaches the browser
 * while the core keeps the stream open, and a browser disconnect closes the upstream stream.
 */
class ApiProxyStreamingTest {

    private HttpServer core;
    private ApiProxyController proxy;
    private final CountDownLatch browserSawFirstEvent = new CountDownLatch(1);
    private final CountDownLatch upstreamSawDisconnect = new CountDownLatch(1);
    private final AtomicBoolean secondEventSentBeforeFirstArrived = new AtomicBoolean();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/api/v1/device-capture/sessions/s1/events", ex -> {
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.getResponseHeaders().add("Set-Cookie", "upstream=1");
            ex.sendResponseHeaders(200, 0); // chunked, open-ended
            OutputStream out = ex.getResponseBody();
            out.write("event:state\ndata:{\"state\":\"LISTENING\"}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                // the second event is only sent once the browser has the first: a buffering proxy never gets here in time
                if (!browserSawFirstEvent.await(5, TimeUnit.SECONDS)) {
                    secondEventSentBeforeFirstArrived.set(true);
                }
                Thread.sleep(300); // a silent interval on the open stream
                out.write("event:preview\ndata:{\"status\":\"PENDING_VERIFICATION\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        core.createContext("/api/v1/device-capture/sessions/s2/events", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
                for (int i = 0; i < 200; i++) { // keep-alive comments until the proxy hangs up
                    out.write((":keepalive " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                upstreamSawDisconnect.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // already closed by the peer
            }
        });
        core.createContext("/api/v1/cases", ex -> {
            byte[] json = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            ex.getResponseBody().write(json);
            ex.close();
        });
        core.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        core.start();
        OAuth2AuthorizedClientManager clients = mock(OAuth2AuthorizedClientManager.class);
        ClientRegistration reg = ClientRegistration.withRegistrationId("wellnessz").clientId("bff").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").authorizationUri("https://idp.test/auth").tokenUri("https://idp.test/token").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at-123", Instant.now(), Instant.now().plusSeconds(300));
        when(clients.authorize(any())).thenReturn(new OAuth2AuthorizedClient(reg, "nurse1", token));
        proxy = new ApiProxyController(clients, RestClient.builder(), "http://127.0.0.1:" + core.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        core.stop(0);
    }

    static MockHttpServletRequest get(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/bff/api" + path);
        r.addHeader("Accept", "text/event-stream");
        return r;
    }

    /** A servlet response that records flushes, like a browser connection that sees bytes when they are flushed. */
    static class RecordingResponse extends MockHttpServletResponse {
        final ByteArrayOutputStream flushed = new ByteArrayOutputStream();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Runnable onFlush = () -> { };
        int failAfterWrites = Integer.MAX_VALUE;
        int writes;
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (++writes > failAfterWrites) {
                    throw new IOException("Broken pipe"); // the browser closed the tab
                }
                pending.write(b, off, len);
            }

            @Override
            public void flush() {
                setCommitted(true);
                flushed.writeBytes(pending.toByteArray());
                pending.reset();
                onFlush.run();
            }
        };

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public void flushBuffer() {
            setCommitted(true);
        }

        String browserText() {
            return flushed.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void serverSentEventsReachTheBrowserChunkByChunk() throws Exception {
        RecordingResponse res = new RecordingResponse();
        res.onFlush = () -> {
            if (res.browserText().contains("LISTENING")) {
                browserSawFirstEvent.countDown();
            }
        };
        CompletableFuture<Void> call = CompletableFuture.runAsync(() -> {
            try {
                proxy.proxy(get("/device-capture/sessions/s1/events"), res, new TestingAuthenticationToken("nurse1", "n/a"));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertThat(browserSawFirstEvent.await(5, TimeUnit.SECONDS)).as("first event flushed while the stream is open").isTrue();
        call.get(10, TimeUnit.SECONDS);
        assertThat(secondEventSentBeforeFirstArrived).isFalse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).startsWith("text/event-stream");
        assertThat(res.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(res.getHeader("Cache-Control")).contains("no-cache");
        assertThat(res.getHeader("Set-Cookie")).isNull();
        assertThat(res.browserText()).contains("event:state").contains("event:preview");
        assertThat(authorization.get()).isEqualTo("Bearer at-123");
    }

    @Test
    void browserDisconnectClosesTheUpstreamStream() throws Exception {
        RecordingResponse res = new RecordingResponse();
        res.failAfterWrites = 2;
        long started = System.nanoTime();
        proxy.proxy(get("/device-capture/sessions/s2/events"), res, new TestingAuthenticationToken("nurse1", "n/a")); // returns quietly
        assertThat(upstreamSawDisconnect.await(5, TimeUnit.SECONDS)).as("the core sees the disconnect").isTrue();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(5);
    }

    @Test
    void ordinaryResponsesKeepTheirLength() throws Exception {
        RecordingResponse res = new RecordingResponse();
        proxy.proxy(new MockHttpServletRequest("GET", "/bff/api/cases"), res, new TestingAuthenticationToken("nurse1", "n/a"));
        assertThat(res.getContentLengthLong()).isEqualTo(10);
        assertThat(res.getHeader("X-Accel-Buffering")).isNull();
        assertThat(res.browserText()).isEqualTo("[{\"id\":1}]");
    }

    @Test
    void streamDetection() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.parseMediaType("text/event-stream;charset=UTF-8"));
        h.setContentLength(10);
        assertThat(ApiProxyController.streaming(h)).isTrue();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(ApiProxyController.streaming(h)).isFalse();
        h.remove("Content-Length");
        assertThat(ApiProxyController.streaming(h)).isTrue(); // unknown length: relayed chunk by chunk
    }
}
