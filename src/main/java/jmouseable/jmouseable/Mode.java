package jmouseable.jmouseable;

public record Mode(String name) {

    private static final Mode defaultMode = new Mode("default-mode");

    public static Mode defaultMode() {
        return defaultMode;
    }

}