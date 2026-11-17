package com.example.budgetapp.service;

import com.example.budgetapp.util.ScreenNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Streaming structural digest; unchanged pages never need XML escaping or an IPC payload. */
final class ScreenTreeFingerprint {
    private final MessageDigest digest;

    ScreenTreeFingerprint() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    byte[] of(ScreenNode root) {
        digest.reset();
        append(root);
        return digest.digest();
    }

    private void append(ScreenNode node) {
        field(node.getText());
        field(node.getContentDescription());
        field(node.getPackageName());
        field(node.getClassName());
        field(node.getViewIdResourceName());
        number(node.getChildCount());
        for (int i = 0; i < node.getChildCount(); i++) append(node.getChild(i));
    }

    private void field(String text) {
        if (text == null) { number(-1); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        number(bytes.length);
        digest.update(bytes);
    }

    private void number(int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
