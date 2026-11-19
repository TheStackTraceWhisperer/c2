package com.example.lobby.handler;

import com.example.server.entity.ActiveSessionRecord;
import com.example.server.repository.ActiveSessionJdbcRepository;

import java.util.Optional;

/**
 * Shared validation helper ensuring a lobby request's session matches the authenticated JWT account.
 */
final class LobbySessionValidator {

  private LobbySessionValidator() {}

  static boolean isSessionAuthorized(ActiveSessionJdbcRepository activeSessionRepository, long sessionId, long accountId) {
    if (activeSessionRepository == null) {
      return true;
    }
    Optional<ActiveSessionRecord> bySession = activeSessionRepository.findBySessionId(sessionId);
    if (bySession.isPresent() && bySession.get().accountId() != accountId) {
      return false;
    }
    Optional<ActiveSessionRecord> byAccount = activeSessionRepository.findByAccountId(accountId);
    if (byAccount.isPresent() && byAccount.get().sessionId() != sessionId) {
      return false;
    }
    return true;
  }
}
