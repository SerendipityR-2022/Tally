package com.example.budgetapp.util;

import java.util.ArrayList;
import java.util.List;

/** Metadata-only counts. A rejected subtree is not traversed for diagnostic purposes. */
public final class ScreenNodeCopyReport {
    private static final int MAX_EXAMPLES = 6;
    private final int[] flags = {ScreenNodePolicy.PACKAGE_MISSING, ScreenNodePolicy.PACKAGE_MISMATCH,
            ScreenNodePolicy.NOT_VISIBLE, ScreenNodePolicy.EDITABLE, ScreenNodePolicy.PASSWORD, ScreenNodePolicy.DATA_SENSITIVE};
    private final int[] rejected = new int[flags.length];
    private final List<String> examples = new ArrayList<>();
    private int visited, copied, nullChildren, blockedSubtrees, maxDepth, rootMask;
    private boolean missingRoot;

    public void missingRoot() { missingRoot = true; }

    public boolean inspect(int depth, int rejectionMask) {
        visited++;
        maxDepth = Math.max(maxDepth, depth);
        if (depth == 0) rootMask = rejectionMask;
        if (rejectionMask == 0) return false;
        blockedSubtrees++;
        for (int i = 0; i < flags.length; ++i) if ((rejectionMask & flags[i]) != 0) rejected[i]++;
        return examples.size() < MAX_EXAMPLES;
    }

    public void example(int depth, int reasons, String metadata) {
        if (examples.size() < MAX_EXAMPLES) {
            examples.add("depth=" + depth + " rejected=" + ScreenNodePolicy.reasons(reasons) + " " + metadata);
        }
    }

    public void copied() { copied++; }
    public void nullChild() { nullChildren++; }
    public String rootResult() {
        return missingRoot ? "SYSTEM_ROOT_NULL" : visited == 0 ? "ROOT_NOT_INSPECTED" : "root=" + ScreenNodePolicy.reasons(rootMask);
    }

    public String summary() {
        StringBuilder out = new StringBuilder(rootResult()).append("; visited=").append(visited)
                .append("; copied=").append(copied).append("; blockedSubtrees=").append(blockedSubtrees)
                .append("; nullChildren=").append(nullChildren).append("; maxDepth=").append(maxDepth);
        for (int i = 0; i < flags.length; ++i) {
            out.append("; ").append(ScreenNodePolicy.reasons(flags[i])).append('=').append(rejected[i]);
        }
        if (blockedSubtrees > 0) out.append("; descendants of blocked nodes NOT inspected");
        for (String example : examples) out.append("\n  ").append(example);
        return out.toString();
    }
}
