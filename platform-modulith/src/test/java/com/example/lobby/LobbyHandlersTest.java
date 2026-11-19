package com.example.lobby;

import com.example.common.protocol.JobId;
import com.example.common.protocol.NationId;
import com.example.common.protocol.RaceId;
import com.example.lobby.dto.*;
import com.example.lobby.handler.CharacterListRequestHandler;
import com.example.lobby.handler.CreateCharacterRequestHandler;
import com.example.lobby.handler.SelectCharacterRequestHandler;
import com.example.login.auth.JwtTokenIssuer;
import com.example.server.SessionContext;
import com.example.server.auth.JwtTokenVerifier;
import com.example.server.auth.RsaKeyProvider;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.ActiveSessionRecord;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.WorldRecord;
import com.example.server.repository.ActiveSessionJdbcRepository;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.WorldJdbcRepository;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyHandlersTest {

  static class SyncDispatcher extends VirtualThreadTaskDispatcher {
    @Override
    public void dispatch(io.netty.channel.ChannelHandlerContext ctx, Runnable task) {
      task.run();
    }
  }

  static class RecordingCharRepo extends CharacterRepository {
    final List<CharacterRecord> saved = new ArrayList<>();

    RecordingCharRepo() {
      super(null);
    }

    @Override
    public List<CharacterRecord> findByAccountId(long accountId) {
      return saved.stream().filter(c -> c.accountId() == accountId).toList();
    }

    @Override
    public Optional<CharacterRecord> findByAccountAndCharacterId(long accountId, long characterId) {
      return saved.stream().filter(c -> c.accountId() == accountId && c.characterId() == characterId).findFirst();
    }

    @Override
    public CharacterRecord save(CharacterRecord record) {
      CharacterRecord withId = new CharacterRecord(
        record.accountId(), 1001L, record.worldId(), record.name(),
        record.raceId(), record.gender(), record.faceId(), record.hairId(), record.sizeId(),
        record.nationId(), record.mainJobId(), record.mainJobLevel(), record.subJobId(), record.subJobLevel(),
        record.zoneId(), record.posX(), record.posY(), record.posZ(), record.rotYaw(), record.rotPitch(),
        record.hpZoneId(), record.hpPosX(), record.hpPosY(), record.hpPosZ(), record.lastSavedAt()
      );
      saved.add(withId);
      return withId;
    }
  }

  private static ActiveSessionJdbcRepository sessionRepo(Map<Long, ActiveSessionRecord> byAccount) {
    return (ActiveSessionJdbcRepository) Proxy.newProxyInstance(
      ActiveSessionJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ActiveSessionJdbcRepository.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "findByAccountId" -> Optional.ofNullable(byAccount.get((Long) args[0]));
        case "findBySessionId" -> {
          long sid = (Long) args[0];
          yield byAccount.values().stream().filter(r -> r.sessionId() == sid).findFirst();
        }
        default -> null;
      }
    );
  }

  private static WorldJdbcRepository worldRepo() {
    return (WorldJdbcRepository) Proxy.newProxyInstance(
      WorldJdbcRepository.class.getClassLoader(),
      new Class<?>[]{WorldJdbcRepository.class},
      (proxy, method, args) -> {
        if ("findById".equals(method.getName())) {
          return Optional.of(new WorldRecord(1, "Asura", "127.0.0.1", 9996, "ONLINE", 4000, Instant.now()));
        }
        return null;
      }
    );
  }

  @Test
  @DisplayName("CreateCharacterRequestHandler validates inputs, persists resolved nationId, and enforces session binding")
  void testCreateCharacterValidationAndSessionBinding() {
    RsaKeyProvider keys = new RsaKeyProvider(null, null, null, null);
    String tokenAcc10 = new JwtTokenIssuer(keys).issueToken(10L, "alice", 15);
    JwtTokenVerifier verifier = new JwtTokenVerifier(keys);
    RecordingCharRepo charRepo = new RecordingCharRepo();

    Map<Long, ActiveSessionRecord> sessions = new HashMap<>();
    sessions.put(10L, new ActiveSessionRecord(10L, 500L, 1, null, Instant.now(), Instant.now()));
    sessions.put(20L, new ActiveSessionRecord(20L, 600L, 1, null, Instant.now(), Instant.now()));

    CreateCharacterRequestHandler handler = new CreateCharacterRequestHandler(
      verifier, charRepo, sessionRepo(sessions), new SyncDispatcher()
    );

    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    try {
      // Mismatched session (Session #600 belongs to account 20, trying to use account 10's JWT)
      ch.attr(SessionContext.SESSION_ID).set(600L);
      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      CreateCharacterResponse mismatchResp = ch.readOutbound();
      assertThat(mismatchResp.success()).isFalse();

      // Bind to correct session 500
      ch.attr(SessionContext.SESSION_ID).set(500L);

      // Invalid name (blank / too short / too long)
      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, " ", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "A", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "A".repeat(33), RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      // Invalid worldId, nationId, raceId, mainJobId
      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 0, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, (short) 99, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "HeroOne", (short) 99, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.WARRIOR
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.BASTOK, JobId.NPC
      ));
      assertThat(((CreateCharacterResponse) ch.readOutbound()).success()).isFalse();

      // Valid creation persists resolved valid nationId
      handler.handle(ch.pipeline().firstContext(), new CreateCharacterRequest(
        tokenAcc10, 1, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1, NationId.WINDURST, JobId.THIEF
      ));
      CreateCharacterResponse ok = ch.readOutbound();
      assertThat(ok.success()).isTrue();
      assertThat(charRepo.saved).hasSize(1);
      assertThat(charRepo.saved.get(0).nationId()).isEqualTo(NationId.WINDURST);
      assertThat(charRepo.saved.get(0).zoneId()).isEqualTo(238);
    } finally {
      ch.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("CharacterListRequestHandler & SelectCharacterRequestHandler enforce session-account binding")
  void testListAndSelectSessionBinding() {
    RsaKeyProvider keys = new RsaKeyProvider(null, null, null, null);
    String tokenAcc10 = new JwtTokenIssuer(keys).issueToken(10L, "alice", 15);
    JwtTokenVerifier verifier = new JwtTokenVerifier(keys);
    RecordingCharRepo charRepo = new RecordingCharRepo();
    charRepo.save(new CharacterRecord(
      10L, 1001L, 1, "HeroOne", RaceId.HUME_MALE, (short) 0, (short) 1, (short) 1, (short) 1,
      NationId.SAN_D_ORIA, JobId.WARRIOR, (short) 1, (short) 0, (short) 0,
      230, 0f, 0f, 0f, 0f, 0f, 230, 0f, 0f, 0f, Instant.now()
    ));

    Map<Long, ActiveSessionRecord> sessions = new HashMap<>();
    sessions.put(10L, new ActiveSessionRecord(10L, 500L, 1, null, Instant.now(), Instant.now()));
    sessions.put(20L, new ActiveSessionRecord(20L, 600L, 1, null, Instant.now(), Instant.now()));
    ActiveSessionJdbcRepository sessionRepo = sessionRepo(sessions);

    CharacterListRequestHandler listHandler = new CharacterListRequestHandler(verifier, charRepo, sessionRepo, new SyncDispatcher());
    SelectCharacterRequestHandler selectHandler = new SelectCharacterRequestHandler(
      verifier, charRepo, worldRepo(), sessionRepo, new SyncDispatcher()
    );

    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ch.attr(SessionContext.SESSION_ID).set(600L); // Wrong session for account 10
    try {
      listHandler.handle(ch.pipeline().firstContext(), new CharacterListRequest(10L, tokenAcc10));
      CharacterListResponse listResp = ch.readOutbound();
      assertThat(listResp.characters()).isEmpty();

      selectHandler.handle(ch.pipeline().firstContext(), new SelectCharacterRequest(tokenAcc10, 1001L));
      SelectCharacterResponse selResp = ch.readOutbound();
      assertThat(selResp.success()).isFalse();
    } finally {
      ch.finishAndReleaseAll();
    }
  }
}
