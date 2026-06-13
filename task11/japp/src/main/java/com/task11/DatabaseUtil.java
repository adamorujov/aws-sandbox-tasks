package com.task11;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DatabaseUtil {

    private static String cachedUsername;
    private static String cachedPassword;
    private static String cachedEndpoint;
    private static Connection cachedConnection;

    public static Connection getConnection() throws Exception {
        if (cachedConnection != null && !cachedConnection.isClosed()) {
            try {
                if (cachedConnection.isValid(2)) {
                    return cachedConnection;
                }
            } catch (SQLException e) {
            }
        }

        if (cachedEndpoint == null) {
            cachedEndpoint = System.getenv("DB_ENDPOINT"); // DB_HOST → DB_ENDPOINT
        }

        if (cachedUsername == null || cachedPassword == null) {
            loadCredentials();
        }

        String dbName = System.getenv("DB_NAME");
        String port = System.getenv("DB_PORT");
        String url = "jdbc:postgresql://" + cachedEndpoint + ":" + port + "/" + dbName;

        try {
            Class.forName("org.postgresql.Driver");
            cachedConnection = DriverManager.getConnection(url, cachedUsername, cachedPassword);
            return cachedConnection;
        } catch (SQLException e) {
            loadCredentials();
            cachedConnection = DriverManager.getConnection(url, cachedUsername, cachedPassword);
            return cachedConnection;
        }
    }

    public static Connection getNewConnection() throws Exception {
        if (cachedEndpoint == null) {
            cachedEndpoint = System.getenv("DB_HOST");
        }

        if (cachedUsername == null || cachedPassword == null) {
            loadCredentials();
        }

        String dbName = System.getenv("DB_NAME");
        String port = System.getenv("DB_PORT");
        String url = "jdbc:postgresql://" + cachedEndpoint + ":" + port + "/" + dbName;

        try {
            Class.forName("org.postgresql.Driver");
            return DriverManager.getConnection(url, cachedUsername, cachedPassword);
        } catch (SQLException e) {
            loadCredentials();
            return DriverManager.getConnection(url, cachedUsername, cachedPassword);
        }
    }

    private static void loadCredentials() {
        String region = System.getenv("REGION");
        String secretName = System.getenv("MASTER_USER_SECRET_NAME");

        AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                .withRegion(region)
                .build();

        GetSecretValueResult result = client.getSecretValue(
                new GetSecretValueRequest()
                        .withSecretId(secretName)
        );

        String secretString = result.getSecretString();
        JsonObject secretJson = JsonParser.parseString(secretString).getAsJsonObject();

        cachedUsername = secretJson.get("username").getAsString();
        cachedPassword = secretJson.get("password").getAsString();

        client.shutdown();
    }
}