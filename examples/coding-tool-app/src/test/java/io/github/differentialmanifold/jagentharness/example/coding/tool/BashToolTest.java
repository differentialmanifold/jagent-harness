package io.github.differentialmanifold.jagentharness.example.coding.tool;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BashToolTest {

    @Test
    void usesCmdOnWindows() {
        assertEquals(Arrays.asList("cmd.exe", "/d", "/s", "/c", "dir"),
                BashTool.shellCommand("dir", "Windows 11"));
    }

    @Test
    void usesShOnUnixLikeSystems() {
        assertEquals(Arrays.asList("/bin/sh", "-lc", "ls"),
                BashTool.shellCommand("ls", "Mac OS X"));
    }
}
