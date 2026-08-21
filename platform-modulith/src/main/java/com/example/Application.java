package com.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Application {

  public static void main(String[] args) {
    try (ApplicationContext context = Micronaut.run(Application.class, args)) {
      log.info("Gateway, LoginServer, and LobbyServer are running. Waiting for connections...");
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.info("Application interrupted.");
    } catch (Exception e) {
      log.error("Error running application", e);
    }
  }
}