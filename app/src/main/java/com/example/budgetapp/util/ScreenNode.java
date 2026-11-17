package com.example.budgetapp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Detached read-only screen tree. Neither source can click, type, or perform node actions. */
public final class ScreenNode {
    private final String text, description, packageName, className, viewId;
    private final List<ScreenNode> children;

    public ScreenNode(String text, String description, String packageName, String className,
            String viewId, List<ScreenNode> children) {
        this.text = text;
        this.description = description;
        this.packageName = packageName;
        this.className = className;
        this.viewId = viewId;
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
    }

    public String getText() { return text; }
    public String getContentDescription() { return description; }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String getViewIdResourceName() { return viewId; }
    public int getChildCount() { return children.size(); }
    public ScreenNode getChild(int index) { return children.get(index); }
}
