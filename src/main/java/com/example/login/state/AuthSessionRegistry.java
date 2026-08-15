package com.example.login.state;

import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class AuthSessionRegistry {

  public record UserSession(long accountId, String authToken, long characterId) {}

  private final Map<Long, UserSession> sessionsByAccount = new ConcurrentHashMap<>();

  public void registerSession(long accountId, String token) {
    sessionsByAccount.put(accountId, new UserSession(accountId, token, 0L));
  }

  public void updateCharacter(long accountId, long characterId) {
    sessionsByAccount.computeIfPresent(accountId, (id, current) ->
      new UserSession(current.accountId(), current.authToken(), characterId)
    );
  }

  public Optional<UserSession> validateAndGet(long accountId, String token) {
    UserSession session = sessionsByAccount.get(accountId);
    if (session != null && session.authToken().equals(token)) {
      return Optional.of(session);
    }
    return Optional.empty();
  }

  public void invalidate(long accountId) {
    sessionsByAccount.remove(accountId);
  }
}