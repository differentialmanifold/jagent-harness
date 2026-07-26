package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches the portable glob subset exposed by the coding search tools.
 */
public final class SearchFileMatcher {

    private static final List<String> EXCLUDED_DIRECTORY_NAMES = Collections.unmodifiableList(
            Arrays.asList(".git", "target", "node_modules", "dist", "build"));

    private final Pattern pattern;
    private final boolean fileNameOnly;

    public SearchFileMatcher(String glob) {
        if (glob == null || glob.isEmpty()) {
            throw new IllegalArgumentException("glob must not be empty");
        }
        if (glob.startsWith("!")) {
            throw new IllegalArgumentException("glob must be an include glob and cannot start with !");
        }
        String normalized = glob.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("glob must be relative to path and cannot start with /");
        }
        if (normalized.endsWith("/")) {
            throw new IllegalArgumentException("glob must match files and cannot end with /");
        }
        fileNameOnly = normalized.indexOf('/') < 0;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("!")) {
            throw new IllegalArgumentException("glob must be an include glob and cannot start with !");
        }
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("glob must be relative to path and cannot start with /");
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("glob must not be empty");
        }
        try {
            pattern = Pattern.compile("^" + translate(normalized, 0, normalized.length()) + "$");
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid glob pattern: " + e.getDescription(), e);
        }
    }

    public boolean matches(Path relativePath) {
        String candidate = relativePath.toString().replace('\\', '/');
        if (fileNameOnly) {
            int separator = candidate.lastIndexOf('/');
            if (separator >= 0) {
                candidate = candidate.substring(separator + 1);
            }
        }
        return pattern.matcher(candidate).matches();
    }

    /**
     * Returns a basename-only glob that ripgrep can safely use as a temporary
     * file type. Path-aware globs are deliberately left for post-filtering.
     */
    public static Optional<String> ripgrepFileTypeGlob(String glob) {
        if (glob == null || glob.isEmpty()) {
            return Optional.empty();
        }
        String candidate = glob.replace('\\', '/');
        while (candidate.startsWith("**/")) {
            candidate = candidate.substring(3);
        }
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        return candidate.indexOf('/') < 0
                ? Optional.of(candidate)
                : Optional.<String>empty();
    }

    public static boolean isExcludedDirectory(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null && EXCLUDED_DIRECTORY_NAMES.contains(fileName.toString());
    }

    public static boolean containsExcludedDirectory(Path relativeFile) {
        int directoryCount = Math.max(0, relativeFile.getNameCount() - 1);
        for (int i = 0; i < directoryCount; i++) {
            if (EXCLUDED_DIRECTORY_NAMES.contains(relativeFile.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    public static List<String> excludedDirectoryNames() {
        return EXCLUDED_DIRECTORY_NAMES;
    }

    private static String translate(String glob, int start, int end) {
        StringBuilder regex = new StringBuilder();
        int index = start;
        while (index < end) {
            char current = glob.charAt(index);
            if (current == '*') {
                int starEnd = index + 1;
                while (starEnd < end && glob.charAt(starEnd) == '*') {
                    starEnd++;
                }
                boolean completeDoubleStarSegment = starEnd - index >= 2
                        && (index == start || glob.charAt(index - 1) == '/')
                        && (starEnd == end || glob.charAt(starEnd) == '/');
                if (completeDoubleStarSegment && starEnd < end) {
                    regex.append("(?:[^/]+/)*");
                    index = starEnd + 1;
                } else if (completeDoubleStarSegment) {
                    regex.append(".*");
                    index = starEnd;
                } else {
                    regex.append("[^/]*");
                    index = starEnd;
                }
                continue;
            }
            if (current == '?') {
                regex.append("[^/]");
                index++;
                continue;
            }
            if (current == '[') {
                int close = findCharacterClassEnd(glob, index + 1, end);
                appendCharacterClass(regex, glob.substring(index + 1, close));
                index = close + 1;
                continue;
            }
            if (current == '{') {
                int close = findBraceEnd(glob, index, end);
                appendAlternatives(regex, glob, index + 1, close);
                index = close + 1;
                continue;
            }
            appendRegexLiteral(regex, current);
            index++;
        }
        return regex.toString();
    }

    private static int findCharacterClassEnd(String glob, int start, int end) {
        for (int index = start; index < end; index++) {
            if (glob.charAt(index) == ']' && index > start) {
                return index;
            }
        }
        throw new IllegalArgumentException("Invalid glob pattern: unclosed character class");
    }

    private static void appendCharacterClass(StringBuilder regex, String content) {
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Invalid glob pattern: empty character class");
        }
        if (content.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Invalid glob pattern: character class cannot contain /");
        }
        regex.append('[');
        int index = 0;
        if (content.charAt(0) == '!') {
            regex.append('^');
            index++;
        } else if (content.charAt(0) == '^') {
            regex.append("\\^");
            index++;
        }
        if (index >= content.length()) {
            throw new IllegalArgumentException("Invalid glob pattern: empty character class");
        }
        for (; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\\' || character == '[' || character == '&') {
                regex.append('\\');
            }
            regex.append(character);
        }
        regex.append(']');
    }

    private static int findBraceEnd(String glob, int start, int end) {
        int depth = 0;
        for (int index = start; index < end; index++) {
            char character = glob.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new IllegalArgumentException("Invalid glob pattern: unclosed alternative");
    }

    private static void appendAlternatives(StringBuilder regex,
                                           String glob,
                                           int start,
                                           int end) {
        List<Integer> separators = new java.util.ArrayList<Integer>();
        int depth = 0;
        for (int index = start; index < end; index++) {
            char character = glob.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            } else if (character == ',' && depth == 0) {
                separators.add(index);
            }
        }
        if (separators.isEmpty()) {
            throw new IllegalArgumentException("Invalid glob pattern: alternative must contain a comma");
        }

        regex.append("(?:");
        int alternativeStart = start;
        for (int i = 0; i <= separators.size(); i++) {
            int alternativeEnd = i < separators.size() ? separators.get(i) : end;
            if (alternativeStart == alternativeEnd) {
                throw new IllegalArgumentException("Invalid glob pattern: empty alternative");
            }
            if (i > 0) {
                regex.append('|');
            }
            regex.append(translate(glob, alternativeStart, alternativeEnd));
            alternativeStart = alternativeEnd + 1;
        }
        regex.append(')');
    }

    private static void appendRegexLiteral(StringBuilder regex, char character) {
        if ("\\.^$|+()[]{}".indexOf(character) >= 0) {
            regex.append('\\');
        }
        regex.append(character);
    }
}
