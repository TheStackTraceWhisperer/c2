package com.example.client.desktop.properties;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties("client")
public class DesktopClientProperties {
    private String defaultServerHost = "127.0.0.1";
    private int defaultServerPort = 8888;
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private String windowTitle = "Final Fantasy XI Desktop Client";
    private boolean vsync = true;
}
