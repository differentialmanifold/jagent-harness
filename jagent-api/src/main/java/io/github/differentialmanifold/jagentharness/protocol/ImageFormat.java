package io.github.differentialmanifold.jagentharness.protocol;

/** Image formats supported in conversation and tool messages. */
public final class ImageFormat {
    private ImageFormat() {}

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public static String detect(byte[] header) {
        for (String type : new String[] {"image/png", "image/jpeg", "image/gif", "image/webp"})
            if (hasImageSignature(type, header)) return type;
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
}
