package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatImageRequest;
import java.util.*;

/** Shared image validation for conversation requests and queued run inputs. */
public final class ImageInputValidator {
    private ImageInputValidator() {}

    static final int MAX_IMAGES = 4;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    static final int MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_NAME_LENGTH = 255;

    public static List<ChatImageRequest> normalize(List<ChatImageRequest> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException(
                    "A message can contain at most " + MAX_IMAGES + " images");
        }

        long totalBytes = 0L;
        List<ChatImageRequest> normalized = new ArrayList<ChatImageRequest>(images.size());
        for (int index = 0; index < images.size(); index++) {
            ChatImageRequest image = images.get(index);
            if (image == null) {
                throw new IllegalArgumentException("Image " + (index + 1) + " is missing");
            }
            String url = image.getUrl() == null ? "" : image.getUrl().trim();
            String mediaType = dataUrlMediaType(url);
            if (mediaType == null) {
                throw new IllegalArgumentException(
                        "Images must be PNG, JPEG, WebP, or GIF base64 data URLs");
            }
            String declaredMediaType =
                    image.getMediaType() == null
                            ? ""
                            : image.getMediaType().trim().toLowerCase(Locale.ROOT);
            if (!declaredMediaType.isEmpty() && !mediaType.equals(declaredMediaType)) {
                throw new IllegalArgumentException("Image mediaType does not match its data URL");
            }
            int comma = url.indexOf(',');
            String encoded = url.substring(comma + 1);
            int maxEncodedLength = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
            if (encoded.isEmpty() || encoded.length() > maxEncodedLength) {
                throw new IllegalArgumentException("Each image must be at most 10 MB");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Image data is not valid base64");
            }
            int decodedBytes = decoded.length;
            if (decodedBytes == 0 || decodedBytes > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Each image must be at most 10 MB");
            }
            if (!hasImageSignature(mediaType, decoded)) {
                throw new IllegalArgumentException(
                        "Image data does not match its declared PNG, JPEG, WebP, or GIF format");
            }
            totalBytes += decodedBytes;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new IllegalArgumentException(
                        "Images in one message must total at most 20 MB");
            }

            ChatImageRequest accepted = new ChatImageRequest();
            accepted.setName(normalizedImageName(image.getName(), mediaType, index));
            accepted.setMediaType(mediaType);
            accepted.setUrl(url);
            accepted.setDetail(normalizedImageDetail(image.getDetail()));
            normalized.add(accepted);
        }
        return normalized;
    }

    private static String dataUrlMediaType(String url) {
        String value = url == null ? "" : url;
        String[] supported = new String[] {"image/png", "image/jpeg", "image/webp", "image/gif"};
        for (String mediaType : supported) {
            String prefix = "data:" + mediaType + ";base64,";
            if (value.length() >= prefix.length()
                    && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return mediaType;
            }
        }
        return null;
    }

    private static boolean hasImageSignature(String mediaType, byte[] data) {
        if ("image/png".equals(mediaType)) {
            return startsWith(data, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
        }
        if ("image/jpeg".equals(mediaType)) {
            return startsWith(data, 0xff, 0xd8, 0xff);
        }
        if ("image/gif".equals(mediaType)) {
            return startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                    || startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61);
        }
        return "image/webp".equals(mediaType)
                && startsWith(data, 0x52, 0x49, 0x46, 0x46)
                && startsWithAt(data, 8, 0x57, 0x45, 0x42, 0x50);
    }

    private static boolean startsWith(byte[] data, int... signature) {
        return startsWithAt(data, 0, signature);
    }

    private static boolean startsWithAt(byte[] data, int offset, int... signature) {
        if (data == null || offset < 0 || data.length - offset < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((data[offset + index] & 0xff) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizedImageName(String name, String mediaType, int index) {
        String value = name == null ? "" : name.trim();
        if (value.length() > MAX_IMAGE_NAME_LENGTH) {
            throw new IllegalArgumentException("Image names must contain at most 255 characters");
        }
        if (!value.isEmpty()) {
            return value;
        }
        String extension =
                "image/jpeg".equals(mediaType) ? "jpg" : mediaType.substring("image/".length());
        return "image-" + (index + 1) + "." + extension;
    }

    private static String normalizedImageDetail(String detail) {
        String value = detail == null ? "" : detail.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (!"auto".equals(value) && !"low".equals(value) && !"high".equals(value)) {
            throw new IllegalArgumentException("Image detail must be auto, low, or high");
        }
        return value;
    }
}
