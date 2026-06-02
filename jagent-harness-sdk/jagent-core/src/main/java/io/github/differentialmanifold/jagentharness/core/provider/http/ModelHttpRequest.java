package io.github.differentialmanifold.jagentharness.core.provider.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModelHttpRequest {

    private final String url;
    private final Map<String, String> headers;
    private final String body;

    public ModelHttpRequest(String url, Map<String, String> headers, String body) {
        this.url = url;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.body = body == null ? "" : body;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }
}
