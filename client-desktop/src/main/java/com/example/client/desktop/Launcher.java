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
        try (ApplicationContext ctx = Micronaut.build(args)
                .mainClass(primarySource)
                .banner(false)
                .start()) {
            ctx.getBean(Engine.class).run();
        }
    }
}
