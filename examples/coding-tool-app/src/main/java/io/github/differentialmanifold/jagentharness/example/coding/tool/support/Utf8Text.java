package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class Utf8Text {

    private Utf8Text() {
    }

    public static void validate(String value, String description) {
        encode(value, description);
    }

    public static byte[] encode(String value, String description) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(description + " must not contain NUL characters");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(description + " must be valid Unicode text", e);
        }
    }
}
