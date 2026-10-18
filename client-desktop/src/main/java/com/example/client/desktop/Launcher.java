package com.example.client.desktop;

import com.example.client.desktop.engine.Engine;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        run(Launcher.class, args);
    }

    public static void run(Class<?> primarySource, String[] args) {
        if (args != null) {
            for (String arg : args) {
                if ("--offline".equalsIgnoreCase(arg) || "-o".equalsIgnoreCase(arg)) {
                    System.setProperty("c2.offline", "true");
                    break;
                }
            }
        }
        try (ApplicationContext ctx = Micronaut.build(args)
                .mainClass(primarySource)
                .banner(false)
                .start()) {
            ctx.getBean(Engine.class).run();
        }
    }
}
