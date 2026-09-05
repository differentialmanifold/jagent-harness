package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ReadListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ErrorResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limits the JSON body of the two chat endpoints that can carry base64 images. */
public class ChatRequestBodyLimitFilter extends OncePerRequestFilter {

    private static final String STREAM_PATH = "/api/chat/stream";
    private static final String RUNS_PATH_PREFIX = "/api/chat/runs/";
    private static final String MESSAGES_PATH_SUFFIX = "/messages";

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    static final class RequestBodyTooLargeException extends IOException {

        private RequestBodyTooLargeException(long maxBytes) {
            super("Chat request body must be at most " + maxBytes + " bytes");
        }
    }

    public ChatRequestBodyLimitFilter(long maxBytes, ObjectMapper objectMapper) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        this.maxBytes = maxBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = pathWithinApplication(request);
        if (STREAM_PATH.equals(path)) {
            return false;
        }
        if (!path.startsWith(RUNS_PATH_PREFIX) || !path.endsWith(MESSAGES_PATH_SUFFIX)) {
            return true;
        }
        String runId = path.substring(
                RUNS_PATH_PREFIX.length(),
                path.length() - MESSAGES_PATH_SUFFIX.length());
        return runId.isEmpty() || runId.indexOf('/') >= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            writePayloadTooLarge(response, new RequestBodyTooLargeException(maxBytes));
            return;
        }

        HttpServletRequest limitedRequest = new LimitedBodyRequest(request, maxBytes);
        try {
            filterChain.doFilter(limitedRequest, response);
        } catch (RequestBodyTooLargeException exception) {
            writePayloadTooLarge(response, exception);
        } catch (ServletException exception) {
            RequestBodyTooLargeException tooLarge = findTooLargeCause(exception);
            if (tooLarge == null) {
                throw exception;
            }
            writePayloadTooLarge(response, tooLarge);
        }
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private RequestBodyTooLargeException findTooLargeCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RequestBodyTooLargeException) {
                return (RequestBodyTooLargeException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private void writePayloadTooLarge(HttpServletResponse response,
                                      RequestBodyTooLargeException exception) throws IOException {
        if (response.isCommitted()) {
            throw exception;
        }
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorResponse(
                        HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                        exception.getMessage()));
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called for this request");
            }
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(super.getInputStream(), maxBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (inputStream != null && reader == null) {
                throw new IllegalStateException("getInputStream() has already been called for this request");
            }
            if (reader == null) {
                Charset charset = getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8
                        : Charset.forName(getCharacterEncoding());
                inputStream = new LimitedServletInputStream(super.getInputStream(), maxBytes);
                reader = new BufferedReader(new InputStreamReader(inputStream, charset));
            }
            return reader;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        private LimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) {
                return value;
            }
            recordRead(1L);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remainingWithSentinel = maxBytes - bytesRead + 1L;
            int boundedLength = (int) Math.min((long) length, Math.max(1L, remainingWithSentinel));
            int count = delegate.read(buffer, offset, boundedLength);
            if (count > 0) {
                recordRead(count);
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0L) {
                return 0L;
            }
            byte[] buffer = new byte[(int) Math.min(8192L, count)];
            long skipped = 0L;
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min((long) buffer.length, count - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min((long) delegate.available(), Math.max(0L, maxBytes - bytesRead));
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("mark/reset is not supported");
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void recordRead(long count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }
    }
}
