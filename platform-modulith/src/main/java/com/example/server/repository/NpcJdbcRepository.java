package com.example.server.repository;

import com.example.server.entity.NpcRecord;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface NpcJdbcRepository extends CrudRepository<NpcRecord, Long> {

  List<NpcRecord> findByZoneId(int zoneId);
}
