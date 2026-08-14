package com.example;

import io.micronaut.context.ApplicationContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Application {

  public static void main(String[] args) {
    try (ApplicationContext context = ApplicationContext.run()) {
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