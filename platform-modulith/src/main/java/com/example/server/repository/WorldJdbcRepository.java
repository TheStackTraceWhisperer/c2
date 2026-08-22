package com.example.server.repository;

import com.example.server.entity.WorldRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface WorldJdbcRepository extends CrudRepository<WorldRecord, Integer> {

  Optional<WorldRecord> findById(int id);

  @Query("""
      UPDATE infra.worlds
      SET host = :host, port = :port, status = :status, last_updated = NOW()
      WHERE id = :id
      """)
  void updateEndpoint(int id, String host, int port, String status);
}