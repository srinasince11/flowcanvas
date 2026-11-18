package com.flowcanvas.graph;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single definition of the prompt-template placeholder syntax.
 *
 * <p>The validator (which checks references statically, before a run) and the executor
 * (which substitutes values during a run) both read placeholders through this class, so the
 * two can never disagree about what counts as a variable.
 */
public final class TemplateVariables {

    /** The reserved variable holding the node's direct input(s). */
    public static final String INPUT = "input";

    /** A node key: what a {@code {{placeholder}}} can address. */
    public static final Pattern KEY = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    /** {@code {{ name }}} with optional inner whitespace. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_\\-]+)\\s*}}");

    private TemplateVariables() {
    }

    /** The distinct variable names referenced by a template, in order of first use. */
    public static Set<String> referencedIn(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    public static boolean isInput(String name) {
        return INPUT.equalsIgnoreCase(name);
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }
}
