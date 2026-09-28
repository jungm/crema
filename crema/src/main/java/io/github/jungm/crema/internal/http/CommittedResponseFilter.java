package io.github.jungm.crema.internal.http;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Makes a committed response final on the paths of the {@code McpApplication}s. Crema writes each response
 * straight to the servlet response and commits it ({@link JaxRs#write}); the JAX-RS runtime and the application's
 * providers then still set a status and headers, which this filter drops silently instead of letting the Runtime
 * log a warning for each request (Liberty: SRVE8115W).
 */
final class CommittedResponseFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response instanceof HttpServletResponse http ? new Final(http) : response);
    }

    /**
     * Ignores every change of status and headers once the response is committed.
     */
    static final class Final extends HttpServletResponseWrapper {

        Final(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            if (!isCommitted()) {
                super.setStatus(sc);
            }
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            if (!isCommitted()) {
                super.sendError(sc, msg);
            }
        }

        @Override
        public void sendError(int sc) throws IOException {
            if (!isCommitted()) {
                super.sendError(sc);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if (!isCommitted()) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!isCommitted()) {
                super.addHeader(name, value);
            }
        }

        @Override
        public void setIntHeader(String name, int value) {
            if (!isCommitted()) {
                super.setIntHeader(name, value);
            }
        }

        @Override
        public void addIntHeader(String name, int value) {
            if (!isCommitted()) {
                super.addIntHeader(name, value);
            }
        }

        @Override
        public void setDateHeader(String name, long date) {
            if (!isCommitted()) {
                super.setDateHeader(name, date);
            }
        }

        @Override
        public void addDateHeader(String name, long date) {
            if (!isCommitted()) {
                super.addDateHeader(name, date);
            }
        }

        @Override
        public void setContentType(String type) {
            if (!isCommitted()) {
                super.setContentType(type);
            }
        }

        @Override
        public void setContentLength(int len) {
            if (!isCommitted()) {
                super.setContentLength(len);
            }
        }

        @Override
        public void setContentLengthLong(long len) {
            if (!isCommitted()) {
                super.setContentLengthLong(len);
            }
        }

        @Override
        public void setCharacterEncoding(String charset) {
            if (!isCommitted()) {
                super.setCharacterEncoding(charset);
            }
        }

        @Override
        public void reset() {
            if (!isCommitted()) {
                super.reset();
            }
        }

        @Override
        public void resetBuffer() {
            if (!isCommitted()) {
                super.resetBuffer();
            }
        }
    }
}
