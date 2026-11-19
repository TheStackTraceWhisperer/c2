package com.example.client.handler;

import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.zone.dto.ZoneStateDeltaMessage;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneStateDeltaHandler implements PacketHandler<ZoneStateDeltaMessage> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, ZoneStateDeltaMessage packet) {
    log.debug("[CLIENT-HANDLER] AoI Delta Tick #{}: Spawns={}, Updates={}, Despawns={}",
      packet.serverTick(),
      packet.spawns() != null ? packet.spawns().size() : 0,
      packet.updates() != null ? packet.updates().size() : 0,
      packet.despawns() != null ? packet.despawns().size() : 0);

    clientState.applyZoneStateDelta(packet);
  }
}