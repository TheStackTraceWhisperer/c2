package com.example.server.repository;

import com.example.server.entity.ZoneInstanceRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ZoneInstanceJdbcRepository extends CrudRepository<ZoneInstanceRecord, Integer> {

  List<ZoneInstanceRecord> findByWorldId(int worldId);

  Optional<ZoneInstanceRecord> findByWorldIdAndZoneIdAndIsStaticTrue(int worldId, int zoneId);

  @Query("""
      UPDATE infra.zone_instances
      SET host = :host, port = :port, status = :status, last_heartbeat = NOW()
      WHERE id = :id
      """)
  void updateEndpoint(int id, String host, int port, String status);
}