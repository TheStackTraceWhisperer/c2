package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.PacketHandler;
import com.example.world.dto.ZoneTransferNotification;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneTransferNotificationHandler implements PacketHandler<ZoneTransferNotification> {

  private final ClientState clientState;

  @Override
  public void handle(ChannelHandlerContext ctx, ZoneTransferNotification packet) {
    log.info("[CLIENT-HANDLER] ZoneTransferNotification received: targetZoneId={}, spawn=({}, {}, {})",
      packet.targetZoneId(), packet.spawnX(), packet.spawnY(), packet.spawnZ());

    clientState.setCurrentZoneId(packet.targetZoneId());
    clientState.setX(packet.spawnX());
    clientState.setY(packet.spawnY());
    clientState.setZ(packet.spawnZ());
    clientState.getRemoteEntities().clear();
    clientState.setConnectionState(ClientConnectionState.IN_ZONE);
    clientState.notifyListeners();
  }
}