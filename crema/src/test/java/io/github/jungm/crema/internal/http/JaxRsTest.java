package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writing replies and SSE streams straight to the servlet response, committing it.
 */
class JaxRsTest {

    /** Records what is written to a servlet response. */
    static final class Recorder {
        int status;
        String contentType;
        boolean committed;
        boolean failWrites;
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        HttpServletResponse response() {
            ServletOutputStream out = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (failWrites) {
                        throw new IOException("Broken pipe");
                    }
                    body.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            };
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setStatus" -> status = (Integer) args[0];
                            case "setHeader" -> headers.put((String) args[0], (String) args[1]);
                            case "setContentType" -> contentType = (String) args[0];
                            case "flushBuffer" -> committed = true;
                            case "getOutputStream" -> {
                                return out;
                            }
                            default -> {
                            }
                        }
                        return null;
                    });
        }

        String text() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void writeCommitsTheReply() throws IOException {
        Recorder recorder = new Recorder();
        JaxRs.write(recorder.response(), new HttpReply(400, Map.of("X-Test", "1"), "{\"a\":1}"));
        assertEquals(400, recorder.status);
        assertEquals("application/json", recorder.contentType);
        assertEquals("1", recorder.headers.get("X-Test"));
        assertEquals("{\"a\":1}", recorder.text());
        assertTrue(recorder.committed);
    }

    @Test
    void writeWithoutBody() throws IOException {
        Recorder recorder = new Recorder();
        JaxRs.write(recorder.response(), new HttpReply(405, Map.of("Allow", "POST"), null));
        assertEquals(405, recorder.status);
        assertEquals(null, recorder.contentType);
        assertEquals("", recorder.text());
        assertTrue(recorder.committed);
    }

    @Test
    void eventStreamWritesOneDataLinePerEvent() throws IOException {
        Recorder recorder = new Recorder();
        McpTransport.EventStream events = JaxRs.eventStream(recorder.response());
        assertTrue(recorder.committed, "committed before the first event");
        assertEquals("text/event-stream", recorder.contentType);
        assertEquals("no", recorder.headers.get("X-Accel-Buffering"));
        events.send("{\"n\":1}").toCompletableFuture().join();
        events.send("{\"n\":2}").toCompletableFuture().join();
        events.close();
        assertEquals("data: {\"n\":1}\n\ndata: {\"n\":2}\n\n", recorder.text());
    }

    @Test
    void failedEventWriteFailsTheSend() throws IOException {
        Recorder recorder = new Recorder();
        McpTransport.EventStream events = JaxRs.eventStream(recorder.response());
        recorder.failWrites = true;
        assertTrue(events.send("{}").toCompletableFuture().isCompletedExceptionally());
        events.close();
    }
}
