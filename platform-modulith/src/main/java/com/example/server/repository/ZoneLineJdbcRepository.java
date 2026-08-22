package com.example.server.repository;

import com.example.server.entity.ZoneLineRecord;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ZoneLineJdbcRepository extends CrudRepository<ZoneLineRecord, Integer> {

  List<ZoneLineRecord> findBySourceZoneId(int sourceZoneId);
}