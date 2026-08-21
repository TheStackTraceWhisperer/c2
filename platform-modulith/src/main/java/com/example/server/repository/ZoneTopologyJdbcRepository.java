package com.example.server.repository;

import com.example.server.entity.ZoneTopologyRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ZoneTopologyJdbcRepository extends CrudRepository<ZoneTopologyRecord, Integer> {

  List<ZoneTopologyRecord> findByRealmId(int realmId);

  Optional<ZoneTopologyRecord> findByIdAndRealmId(int id, int realmId);

  @Query("""
      UPDATE zones
      SET host = :host, port = :port, status = :status, last_heartbeat = NOW()
      WHERE id = :id
      """)
  void updateEndpoint(int id, String host, int port, String status);
}