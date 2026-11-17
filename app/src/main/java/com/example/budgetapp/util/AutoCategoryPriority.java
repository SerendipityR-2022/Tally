package com.example.budgetapp.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.example.budgetapp.util.AutoCategoryClassifier.Source;

/** Confirmed matches and then AI precede the configurable local fallback strategies. */
public final class AutoCategoryPriority {
    private static final List<Source> DEFAULT = Collections.unmodifiableList(Arrays.asList(
            Source.LEARNED, Source.AI, Source.RULE, Source.LOCAL, Source.HINT, Source.FALLBACK));
    private final List<Source> sources;

    public AutoCategoryPriority(List<Source> requested) {
        List<Source> result = new ArrayList<>();
        result.add(Source.LEARNED);
        result.add(Source.AI);
        if (requested != null) {
            for (Source source : requested) {
                if (source != null && source != Source.FALLBACK && !result.contains(source)) result.add(source);
            }
        }
        for (Source source : DEFAULT) if (!result.contains(source)) result.add(source);
        sources = Collections.unmodifiableList(result);
    }

    public static AutoCategoryPriority defaults() { return new AutoCategoryPriority(DEFAULT); }

    public static AutoCategoryPriority parse(String stored) {
        List<Source> result = new ArrayList<>();
        if (stored != null) {
            for (String name : stored.split(",")) {
                try { result.add(Source.valueOf(name.trim())); }
                catch (IllegalArgumentException ignored) { /* forward-compatible backup restore */ }
            }
        }
        return new AutoCategoryPriority(result);
    }

    public List<Source> getSources() { return sources; }
    public boolean canMove(int position) { return position >= 2 && position < sources.size() - 1; }

    public AutoCategoryPriority move(int from, int to) {
        if (!canMove(from) || !canMove(to)) return this;
        List<Source> result = new ArrayList<>(sources);
        result.add(to, result.remove(from));
        return new AutoCategoryPriority(result);
    }

    public String serialize() {
        StringBuilder text = new StringBuilder();
        for (Source source : sources) {
            if (text.length() > 0) text.append(',');
            text.append(source.name());
        }
        return text.toString();
    }
}
