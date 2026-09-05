package io.github.differentialmanifold.jagentharness.core.message;

/**
 * An image attached to a user message.
 *
 * <p>The URL may be an HTTP(S) URL or a {@code data:image/...;base64,...} URL. Web-facing
 * integrations should apply their own trust and size limits before creating an instance.</p>
 */
public class MessageImage {

    private String name;
    private String mediaType;
    private String url;
    private String detail;

    public MessageImage() {
    }

    public MessageImage(String name, String mediaType, String url) {
        this(name, mediaType, url, null);
    }

    public MessageImage(String name, String mediaType, String url, String detail) {
        this.name = name;
        this.mediaType = mediaType;
        this.url = url;
        this.detail = detail;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
