package org.mzpeak.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal command-line parser shared by the example tools: positional arguments plus {@code --key value} /
 * {@code --key=value} options ({@code --flag} → {@code "true"}; repeated options are comma-joined).
 */
final class ExampleArgs {

    final List<String> positional = new ArrayList<>();
    final Map<String, String> options = new HashMap<>();

    ExampleArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value;
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    value = key.substring(eq + 1);
                    key = key.substring(0, eq);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    value = "true";
                }
                options.merge(key, value, (o, n) -> o + "," + n);
            } else {
                positional.add(a);
            }
        }
    }

    String opt(String key, String def) {
        return options.getOrDefault(key, def);
    }

    double optDouble(String key, double def) {
        String v = options.get(key);
        return v == null ? def : Double.parseDouble(v);
    }

    int optInt(String key, int def) {
        String v = options.get(key);
        return v == null ? def : Integer.parseInt(v);
    }
}
