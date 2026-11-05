package com.ibm.websphere.ras;

/**
 * Test-only stand-in for the Liberty trace class that {@code ToolCallException} calls in its static
 * initializer. On Liberty the real class is provided by the server. Not packaged in the WAR.
 */
public final class Tr {

    private Tr() {
    }

    public static TraceComponent register(String name, Class<?> aClass, String group, String bundle) {
        return new TraceComponent();
    }
}
