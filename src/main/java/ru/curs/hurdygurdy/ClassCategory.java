package ru.curs.hurdygurdy;

public enum ClassCategory {
    DTO("dto"),
    CONTROLLER("controller");

    public String getPackageName() {
        return packageName;
    }

    private final String packageName;

    ClassCategory(String packageName) {
        this.packageName = packageName;
    }
}
