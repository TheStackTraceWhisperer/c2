package com.example.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

public class DatabaseAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String dbUrl = System.getProperty("datasources.default.url", "jdbc:postgresql://localhost:5432/mmo_platform");
        String host = "localhost";
        int port = 5432;

        try {
            if (dbUrl.startsWith("jdbc:postgresql://")) {
                String clean = dbUrl.substring("jdbc:postgresql://".length());
                int slash = clean.indexOf('/');
                if (slash > 0) {
                    clean = clean.substring(0, slash);
                }
                int colon = clean.indexOf(':');
                if (colon > 0) {
                    host = clean.substring(0, colon);
                    port = Integer.parseInt(clean.substring(colon + 1));
                } else {
                    host = clean;
                }
            }
        } catch (Exception ignored) {
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return ConditionEvaluationResult.enabled("Database is reachable at " + host + ":" + port);
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "Skipping integration test: PostgreSQL database is not reachable at " + host + ":" + port +
                    ". Start the k3d cluster with scripts/run-e2e.sh to run full cluster integration tests."
            );
        }
    }
}
