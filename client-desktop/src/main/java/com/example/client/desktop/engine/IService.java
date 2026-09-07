package com.example.client.desktop.engine;

public interface IService {
    default void start()          {}
    default void stop()           {}
    default void update()         {}
    default void update(float dt) {}
    default void postUpdate()     {}
    default int  executionOrder() { return 0; }
}
