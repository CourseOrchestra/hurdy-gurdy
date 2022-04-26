package ru.curs.hurdygurdy;

public class DTOMeta {
    private final String className;
    private final String packageName;
    private final String fileName;
    private final boolean nullable;

    public DTOMeta(String className, String packageName, String fileName, boolean nullable) {
        this.className = className;
        this.packageName = packageName;
        this.fileName = fileName;
        this.nullable = nullable;
    }

    public String getClassName() {
        return className;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isNullable() {
        return nullable;
    }
}
