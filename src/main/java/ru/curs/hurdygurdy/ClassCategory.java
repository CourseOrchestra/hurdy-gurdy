package ru.curs.hurdygurdy;

public enum ClassCategory {
    /**
     * Data transition object class.
     */
    DTO("dto"),
    /**
     * Controller (api) class.
     */
    CONTROLLER("controller");

    private final String packageName;

    ClassCategory(String packageName) {
        this.packageName = packageName;
    }

    public String getPackageName() {
        return packageName;
    }
}
