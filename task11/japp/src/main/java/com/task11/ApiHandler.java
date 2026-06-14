package com.task11;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.environment.ValueTransformer;

import java.sql.*;
import java.util.*;

@LambdaHandler(
    lambdaName = "api_handler",
    roleName = "api_handler-role",
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "REGION", value = "${region}"),
    @EnvironmentVariable(key = "DB_ENDPOINT", value = "logistic-cluster",
        valueTransformer = ValueTransformer.RDS_DB_CLUSTER_NAME_TO_ENDPOINT),
    @EnvironmentVariable(key = "MASTER_USER_SECRET_NAME", value = "logistic-cluster",
        valueTransformer = ValueTransformer.RDS_DB_CLUSTER_NAME_TO_MASTER_USER_SECRET_NAME)
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String httpMethod = event.getHttpMethod();
        String path = event.getResource();
        Map<String, String> pathParams = event.getPathParameters();

        try (Connection conn = getConnection()) {
            if ("POST".equals(httpMethod) && "/initdb".equals(path)) {
                return initDb(conn);
            } else if ("GET".equals(httpMethod) && "/shipments/{shipmentId}".equals(path)) {
                return getShipment(conn, pathParams.get("shipmentId"));
            } else if ("POST".equals(httpMethod) && "/shipments".equals(path)) {
                return createShipment(conn, event.getBody());
            } else if ("PATCH".equals(httpMethod) && "/shipments/{shipmentId}".equals(path)) {
                return updateShipment(conn, pathParams.get("shipmentId"), event.getBody());
            } else if ("DELETE".equals(httpMethod) && "/shipments/{shipmentId}".equals(path)) {
                return deleteShipment(conn, pathParams.get("shipmentId"));
            } else if ("GET".equals(httpMethod) && "/carriers/{carrierId}".equals(path)) {
                return getCarrier(conn, pathParams.get("carrierId"));
            } else if ("POST".equals(httpMethod) && "/carriers".equals(path)) {
                return createCarrier(conn, event.getBody());
            } else if ("PATCH".equals(httpMethod) && "/carriers/{carrierId}".equals(path)) {
                return updateCarrier(conn, pathParams.get("carrierId"), event.getBody());
            } else if ("DELETE".equals(httpMethod) && "/carriers/{carrierId}".equals(path)) {
                return deleteCarrier(conn, pathParams.get("carrierId"));
            } else if ("GET".equals(httpMethod) && "/statusupdates/{shipmentId}".equals(path)) {
                return getStatusUpdates(conn, pathParams.get("shipmentId"));
            } else if ("POST".equals(httpMethod) && "/statusupdates".equals(path)) {
                return createStatusUpdate(conn, event.getBody());
            } else {
                return response(404, "{\"message\":\"Not Found\"}");
            }
        } catch (Exception e) {
            context.getLogger().log("ERROR: " + e.getMessage());
            return response(500, "{\"message\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    // ==================== DB CONNECTION ====================

    private Connection getConnection() throws Exception {
        String endpoint = System.getenv("DB_ENDPOINT");
        String secretName = System.getenv("MASTER_USER_SECRET_NAME");
        String region = System.getenv("REGION");

        AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                .withRegion(region)
                .build();

        GetSecretValueResult secretValue = client.getSecretValue(
                new GetSecretValueRequest().withSecretId(secretName));

        Map<String, String> secret = objectMapper.readValue(secretValue.getSecretString(), Map.class);
        String username = secret.get("username");
        String password = secret.get("password");

        String url = "jdbc:postgresql://" + endpoint + ":5432/logisticdb";
        return DriverManager.getConnection(url, username, password);
    }

    // ==================== INITDB ====================

    private APIGatewayProxyResponseEvent initDb(Connection conn) throws SQLException {
        String createStatusType =
                "DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'statustype') THEN " +
                "CREATE TYPE StatusType AS ENUM('CREATED','IN_TRANSIT','DELAYED','DELIVERED','CANCELLED'); " +
                "END IF; END $$;";

        String createShipments =
                "CREATE TABLE IF NOT EXISTS shipments (" +
                "shipment_id VARCHAR(50) PRIMARY KEY," +
                "order_id VARCHAR(50)," +
                "origin VARCHAR(100)," +
                "destination VARCHAR(100)," +
                "weight_kg DECIMAL(10,2)," +
                "created_at TIMESTAMPTZ)";

        String createCarriers =
                "CREATE TABLE IF NOT EXISTS carriers (" +
                "carrier_id VARCHAR(50) PRIMARY KEY," +
                "name VARCHAR(100)," +
                "email VARCHAR(100)," +
                "phone VARCHAR(20)," +
                "is_active BOOLEAN)";

        String createStatusUpdates =
                "CREATE TABLE IF NOT EXISTS status_updates (" +
                "update_id SERIAL PRIMARY KEY," +
                "shipment_id VARCHAR(50) REFERENCES shipments(shipment_id)," +
                "carrier_id VARCHAR(50) REFERENCES carriers(carrier_id)," +
                "status StatusType," +
                "location VARCHAR(100)," +
                "notes TEXT," +
                "timestamp TIMESTAMPTZ)";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createStatusType);
            stmt.execute(createShipments);
            stmt.execute(createCarriers);
            stmt.execute(createStatusUpdates);
        }
        return response(200, "{\"message\":\"Database initialized successfully\"}");
    }

    // ==================== SHIPMENTS ====================

    private APIGatewayProxyResponseEvent getShipment(Connection conn, String shipmentId) throws Exception {
        String sql = "SELECT * FROM shipments WHERE shipment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shipmentId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("shipment_id", rs.getString("shipment_id"));
                result.put("order_id", rs.getString("order_id"));
                result.put("origin", rs.getString("origin"));
                result.put("destination", rs.getString("destination"));
                result.put("weight_kg", rs.getDouble("weight_kg"));
                result.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                return response(200, objectMapper.writeValueAsString(result));
            } else {
                return response(404, "{\"message\":\"Shipment not found\"}");
            }
        }
    }

    private APIGatewayProxyResponseEvent createShipment(Connection conn, String body) throws Exception {
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String sql = "INSERT INTO shipments(shipment_id, order_id, origin, destination, weight_kg, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("shipment_id"));
            ps.setString(2, (String) data.get("order_id"));
            ps.setString(3, (String) data.get("origin"));
            ps.setString(4, (String) data.get("destination"));
            ps.setDouble(5, ((Number) data.get("weight_kg")).doubleValue());
            ps.executeUpdate();
        }
        return response(201, "{\"message\":\"Shipment created successfully\"}");
    }

    private APIGatewayProxyResponseEvent updateShipment(Connection conn, String shipmentId, String body) throws Exception {
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String sql = "UPDATE shipments SET order_id=?, origin=?, destination=?, weight_kg=? WHERE shipment_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("order_id"));
            ps.setString(2, (String) data.get("origin"));
            ps.setString(3, (String) data.get("destination"));
            ps.setDouble(4, ((Number) data.get("weight_kg")).doubleValue());
            ps.setString(5, shipmentId);
            ps.executeUpdate();
        }
        return response(200, "{\"message\":\"Shipment updated successfully\"}");
    }

    private APIGatewayProxyResponseEvent deleteShipment(Connection conn, String shipmentId) throws SQLException {
        String sql = "DELETE FROM shipments WHERE shipment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shipmentId);
            ps.executeUpdate();
        }
        return response(200, "{\"message\":\"Shipment deleted successfully\"}");
    }

    // ==================== CARRIERS ====================

    private APIGatewayProxyResponseEvent getCarrier(Connection conn, String carrierId) throws Exception {
        String sql = "SELECT * FROM carriers WHERE carrier_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, carrierId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("carrier_id", rs.getString("carrier_id"));
                result.put("name", rs.getString("name"));
                result.put("email", rs.getString("email"));
                result.put("phone", rs.getString("phone"));
                result.put("is_active", rs.getBoolean("is_active"));
                return response(200, objectMapper.writeValueAsString(result));
            } else {
                return response(404, "{\"message\":\"Carrier not found\"}");
            }
        }
    }

    private APIGatewayProxyResponseEvent createCarrier(Connection conn, String body) throws Exception {
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String sql = "INSERT INTO carriers(carrier_id, name, email, phone, is_active) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("carrier_id"));
            ps.setString(2, (String) data.get("name"));
            ps.setString(3, (String) data.get("email"));
            ps.setString(4, (String) data.get("phone"));
            ps.setBoolean(5, (Boolean) data.get("is_active"));
            ps.executeUpdate();
        }
        return response(201, "{\"message\":\"Carrier created successfully\"}");
    }

    private APIGatewayProxyResponseEvent updateCarrier(Connection conn, String carrierId, String body) throws Exception {
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String sql = "UPDATE carriers SET name=?, email=?, phone=?, is_active=? WHERE carrier_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("name"));
            ps.setString(2, (String) data.get("email"));
            ps.setString(3, (String) data.get("phone"));
            ps.setBoolean(4, (Boolean) data.get("is_active"));
            ps.setString(5, carrierId);
            ps.executeUpdate();
        }
        return response(200, "{\"message\":\"Carrier updated successfully\"}");
    }

    private APIGatewayProxyResponseEvent deleteCarrier(Connection conn, String carrierId) throws SQLException {
        String sql = "DELETE FROM carriers WHERE carrier_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, carrierId);
            ps.executeUpdate();
        }
        return response(200, "{\"message\":\"Carrier deleted successfully\"}");
    }

    // ==================== STATUS UPDATES ====================

    private APIGatewayProxyResponseEvent getStatusUpdates(Connection conn, String shipmentId) throws Exception {
        String sql = "SELECT * FROM status_updates WHERE shipment_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shipmentId);
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> results = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("update_id", rs.getInt("update_id"));
                row.put("shipment_id", rs.getString("shipment_id"));
                row.put("carrier_id", rs.getString("carrier_id"));
                row.put("status", rs.getString("status"));
                row.put("location", rs.getString("location"));
                row.put("notes", rs.getString("notes"));
                row.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                results.add(row);
            }
            return response(200, objectMapper.writeValueAsString(results));
        }
    }

    private APIGatewayProxyResponseEvent createStatusUpdate(Connection conn, String body) throws Exception {
        Map<String, Object> data = objectMapper.readValue(body, Map.class);
        String sql = "INSERT INTO status_updates(shipment_id, carrier_id, status, location, notes, timestamp) " +
                     "VALUES (?, ?, ?::StatusType, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) data.get("shipment_id"));
            ps.setString(2, (String) data.get("carrier_id"));
            ps.setString(3, (String) data.get("status"));
            ps.setString(4, (String) data.get("location"));
            ps.setString(5, (String) data.get("notes"));
            ps.executeUpdate();
        }
        return response(201, "{\"message\":\"Status update created successfully\"}");
    }

    // ==================== HELPER ====================

    private APIGatewayProxyResponseEvent response(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body);
    }
}