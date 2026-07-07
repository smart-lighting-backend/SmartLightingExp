package com.experiment.smartlightingexp.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserControllerDisplayIdTest {

    @Test
    void userListReturnsContinuousDisplayId() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/experiment/smartlightingexp/controller/UserController.java"));

        assertTrue(source.contains("displayIdCounter"));
        assertTrue(source.contains("item.put(\"displayId\", displayIdCounter.getAndIncrement())"));
    }
}
