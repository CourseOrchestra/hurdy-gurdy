package ru.curs.hurdygurdy;

public final class CaseUtils {
    private CaseUtils() {

    }

    public static String pathToCamel(String pathText) {
        if (pathText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pathText.length(); i++) {
            char c = pathText.charAt(i);
            switch (state) {
                case 0:
                    if (!(c == '/' || c == '{')) {
                        result.append(c);
                        state = 1;
                    }
                    break;
                case 1:
                    if (c == '/') {
                        state = 2;
                    } else if (c != '}') {
                        result.append(c);
                    }
                    break;
                case 2:
                    if (!(c == '/' || c == '{')) {
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    break;
            }
        }
        return result.toString();
    }

    public static String snakeToCamel(String snakeText) {
        return snakeToCamel(snakeText, false);
    }

    public static String snakeToCamel(String snakeText, boolean capitalizeFirst) {
        if (snakeText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < snakeText.length(); i++) {
            char c = snakeText.charAt(i);
            switch (state) {
                case 0:
                    if (capitalizeFirst) {
                        result.append(Character.toUpperCase(c));
                    } else {
                        result.append(c);
                    }
                    state = 1;
                    break;
                case 1:
                    result.append(c);
                    if (c != '_') {
                        state = 2;
                    }
                    break;
                case 2:
                    if (c == '_') {
                        state = 3;
                    } else {
                        result.append(c);
                    }
                    break;
                case 3:
                    if (c != '_') {
                        result.append(Character.toUpperCase(c));
                        state = 2;
                    }
                    break;
            }
        }
        return result.toString();
    }

    public static String kebabToCamel(String kebabPascalText) {
        if (kebabPascalText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < kebabPascalText.length(); i++) {
            char c = kebabPascalText.charAt(i);
            switch (state) {
                case 0:
                    result.append(Character.toLowerCase(c));
                    state = 1;
                    break;
                case 1:
                    result.append(c);
                    if (c != '-') {
                        state = 2;
                    }
                    break;
                case 2:
                    if (c == '-') {
                        state = 3;
                    } else {
                        result.append(c);
                    }
                    break;
                case 3:
                    if (c != '-') {
                        result.append(Character.toUpperCase(c));
                        state = 2;
                    }
                    break;
            }
        }
        return result.toString();
    }
}
