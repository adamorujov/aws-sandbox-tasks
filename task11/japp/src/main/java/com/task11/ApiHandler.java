package com.task11;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

@LambdaHandler(
        lambdaName = "api_handler",
        roleName = "api_handler-role",
        runtime = DeploymentRuntime.JAVA11,
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
        timeout = 120,
        memory = 512
)
@LambdaUrlConfig(
        authType = AuthType.NONE,
        invokeMode = InvokeMode.BUFFERED
)
@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "DB_ENDPOINT", value = "${db_endpoint}"),
        @EnvironmentVariable(key = "MASTER_USER_SECRET_NAME", value = "${master_user_secret_name}"),
        @EnvironmentVariable(key = "DB_NAME", value = "${db_name}"),
        @EnvironmentVariable(key = "DB_PORT", value = "${db_port}"),
        @EnvironmentVariable(key = "REGION", value = "${region}")
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        String path = request.getResource();
        String method = request.getHttpMethod();
        logger.log("Received request: " + method + " " + path);

        try {
            // ROUTING
            if ("/initdb".equals(path) && "POST".equals(method)) {
                return initDb(logger);
            }
            // SHIPMENTS
            else if ("/shipments".equals(path) && "POST".equals(method)) {
                return createShipment(request, logger);
            } else if ("/shipments/{shipmentId}".equals(path) && "GET".equals(method)) {
                return getShipment(request, logger);
            } else if ("/shipments/{shipmentId}".equals(path) && "PATCH".equals(method)) {
                return updateShipment(request, logger);
            } else if ("/shipments/{shipmentId}".equals(path) && "DELETE".equals(method)) {
                return deleteShipment(request, logger);
            }
            // CARRIERS
            else if ("/carriers".equals(path) && "POST".equals(method)) {
                return createCarrier(request, logger);
            } else if ("/carriers/{carrierId}".equals(path) && "GET".equals(method)) {
                return getCarrier(request, logger);
            } else if ("/carriers/{carrierId}".equals(path) && "PATCH".equals(method)) {
                return updateCarrier(request, logger);
            } else if ("/carriers/{carrierId}".equals(path) && "DELETE".equals(method)) {
                return deleteCarrier(request, logger);
            }
            // STATUS UPDATES
            else if ("/statusupdates".equals(path) && "POST".equals(method)) {
                return createStatusUpdate(request, logger);
            } else if ("/statusupdates/{shipmentId}".equals(path) && "GET".equals(method)) {
                return getStatusUpdates(request, logger);
            }

            return buildResponse(400, "{\"message\":\"Bad Request: Unknown route\"}");

        } catch (Exception e) {
            logger.log("ERROR: " + e.getMessage());
            return buildResponse(500, "{\"message\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    // ======================== INIT DB ========================

    private APIGatewayProxyResponseEvent initDb(LambdaLogger logger) throws Exception {
		logger.log("Initializing database tables...");

		try (Connection conn = DatabaseUtil.getConnection();
			Statement stmt = conn.createStatement()) {

			stmt.execute(
					"DO $$ BEGIN " +
					"CREATE TYPE StatusType AS ENUM('CREATED', 'IN_TRANSIT', 'DELAYED', 'DELIVERED', 'CANCELLED'); " +
					"EXCEPTION WHEN duplicate_object THEN null; " +
					"END $$;"
			);

			stmt.execute(
					"CREATE TABLE IF NOT EXISTS shipments (" +
					"shipment_id VARCHAR(50) PRIMARY KEY, " +
					"order_id VARCHAR(50), " +
					"origin VARCHAR(100), " +
					"destination VARCHAR(100), " +
					"weight_kg DECIMAL(10,2), " +
					"created_at TIMESTAMPTZ" +
					")"
			);

			stmt.execute(
					"CREATE TABLE IF NOT EXISTS carriers (" +
					"carrier_id VARCHAR(50) PRIMARY KEY, " +
					"name VARCHAR(100), " +
					"email VARCHAR(100), " +
					"phone VARCHAR(20), " +
					"is_active BOOLEAN" +
					")"
			);

			stmt.execute(
					"CREATE TABLE IF NOT EXISTS status_updates (" +
					"update_id SERIAL PRIMARY KEY, " +
					"shipment_id VARCHAR(50) REFERENCES shipments(shipment_id), " +
					"carrier_id VARCHAR(50) REFERENCES carriers(carrier_id), " +
					"status StatusType, " +
					"location VARCHAR(100), " +
					"notes TEXT, " +
					"\"timestamp\" TIMESTAMPTZ" +
					")"
			);

			logger.log("Database initialized successfully");
			// ✅ Boş body ilə 200 qaytarır
			return buildResponse(200, "");
		}
	}

    // ======================== SHIPMENTS ========================

    private APIGatewayProxyResponseEvent createShipment(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
        logger.log("Creating shipment: " + body.get("shipment_id").getAsString());

        String sql = "INSERT INTO shipments (shipment_id, order_id, origin, destination, weight_kg, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, body.get("shipment_id").getAsString());
            ps.setString(2, body.get("order_id").getAsString());
            ps.setString(3, body.get("origin").getAsString());
            ps.setString(4, body.get("destination").getAsString());
            ps.setBigDecimal(5, body.get("weight_kg").getAsBigDecimal());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();

            logger.log("Shipment created successfully");
            return buildResponse(201, "{\"message\":\"Shipment created successfully\"}");
        }
    }

    private APIGatewayProxyResponseEvent getShipment(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String shipmentId = request.getPathParameters().get("shipmentId");
        logger.log("Getting shipment: " + shipmentId);

        String sql = "SELECT * FROM shipments WHERE shipment_id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, shipmentId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("shipment_id", rs.getString("shipment_id"));
                result.put("order_id", rs.getString("order_id"));
                result.put("origin", rs.getString("origin"));
                result.put("destination", rs.getString("destination"));
                result.put("weight_kg", rs.getDouble("weight_kg"));

                Timestamp ts = rs.getTimestamp("created_at");
                if (ts != null) {
                    result.put("created_at", ts.toInstant().atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                } else {
                    result.put("created_at", null);
                }

                return buildResponse(200, gson.toJson(result));
            }

            return buildResponse(404, "{\"message\":\"Shipment not found\"}");
        }
    }

    private APIGatewayProxyResponseEvent updateShipment(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String shipmentId = request.getPathParameters().get("shipmentId");
        JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
        logger.log("Updating shipment: " + shipmentId);

        StringBuilder sql = new StringBuilder("UPDATE shipments SET ");
        List<Object> params = new ArrayList<>();
        List<Integer> types = new ArrayList<>();

        if (body.has("order_id") && !body.get("order_id").isJsonNull()) {
            sql.append("order_id = ?, ");
            params.add(body.get("order_id").getAsString());
            types.add(Types.VARCHAR);
        }
        if (body.has("origin") && !body.get("origin").isJsonNull()) {
            sql.append("origin = ?, ");
            params.add(body.get("origin").getAsString());
            types.add(Types.VARCHAR);
        }
        if (body.has("destination") && !body.get("destination").isJsonNull()) {
            sql.append("destination = ?, ");
            params.add(body.get("destination").getAsString());
            types.add(Types.VARCHAR);
        }
        if (body.has("weight_kg") && !body.get("weight_kg").isJsonNull()) {
            sql.append("weight_kg = ?, ");
            params.add(body.get("weight_kg").getAsBigDecimal());
            types.add(Types.DECIMAL);
        }

        if (params.isEmpty()) {
            return buildResponse(400, "{\"message\":\"No fields to update\"}");
        }

        // Son vergülü sil
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE shipment_id = ?");
        params.add(shipmentId);
        types.add(Types.VARCHAR);

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();

            logger.log("Shipment updated successfully");
            return buildResponse(200, "{\"message\":\"Shipment updated successfully\"}");
        }
    }

    private APIGatewayProxyResponseEvent deleteShipment(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String shipmentId = request.getPathParameters().get("shipmentId");
        logger.log("Deleting shipment: " + shipmentId);

        try (Connection conn = DatabaseUtil.getConnection()) {
            // Əvvəlcə əlaqəli status_updates-ləri sil (foreign key constraint)
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM status_updates WHERE shipment_id = ?")) {
                ps.setString(1, shipmentId);
                ps.executeUpdate();
            }

            // Sonra shipment-i sil
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM shipments WHERE shipment_id = ?")) {
                ps.setString(1, shipmentId);
                ps.executeUpdate();
            }

            logger.log("Shipment deleted successfully");
            return buildResponse(200, "{\"message\":\"Shipment deleted successfully\"}");
        }
    }

    // ======================== CARRIERS ========================

    private APIGatewayProxyResponseEvent createCarrier(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
        logger.log("Creating carrier: " + body.get("carrier_id").getAsString());

        String sql = "INSERT INTO carriers (carrier_id, name, email, phone, is_active) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, body.get("carrier_id").getAsString());
            ps.setString(2, body.get("name").getAsString());
            ps.setString(3, body.get("email").getAsString());
            ps.setString(4, body.get("phone").getAsString());
            ps.setBoolean(5, body.get("is_active").getAsBoolean());
            ps.executeUpdate();

            logger.log("Carrier created successfully");
            return buildResponse(201, "{\"message\":\"Carrier created successfully\"}");
        }
    }

    private APIGatewayProxyResponseEvent getCarrier(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String carrierId = request.getPathParameters().get("carrierId");
        logger.log("Getting carrier: " + carrierId);

        String sql = "SELECT * FROM carriers WHERE carrier_id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, carrierId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("carrier_id", rs.getString("carrier_id"));
                result.put("name", rs.getString("name"));
                result.put("email", rs.getString("email"));
                result.put("phone", rs.getString("phone"));
                result.put("is_active", rs.getBoolean("is_active"));

                return buildResponse(200, gson.toJson(result));
            }

            return buildResponse(404, "{\"message\":\"Carrier not found\"}");
        }
    }

    private APIGatewayProxyResponseEvent updateCarrier(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String carrierId = request.getPathParameters().get("carrierId");
        JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
        logger.log("Updating carrier: " + carrierId);

        StringBuilder sql = new StringBuilder("UPDATE carriers SET ");
        List<Object> params = new ArrayList<>();

        if (body.has("name") && !body.get("name").isJsonNull()) {
            sql.append("name = ?, ");
            params.add(body.get("name").getAsString());
        }
        if (body.has("email") && !body.get("email").isJsonNull()) {
            sql.append("email = ?, ");
            params.add(body.get("email").getAsString());
        }
        if (body.has("phone") && !body.get("phone").isJsonNull()) {
            sql.append("phone = ?, ");
            params.add(body.get("phone").getAsString());
        }
        if (body.has("is_active") && !body.get("is_active").isJsonNull()) {
            sql.append("is_active = ?, ");
            params.add(body.get("is_active").getAsBoolean());
        }

        if (params.isEmpty()) {
            return buildResponse(400, "{\"message\":\"No fields to update\"}");
        }

        sql.setLength(sql.length() - 2);
        sql.append(" WHERE carrier_id = ?");
        params.add(carrierId);

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();

            logger.log("Carrier updated successfully");
            return buildResponse(200, "{\"message\":\"Carrier updated successfully\"}");
        }
    }

    private APIGatewayProxyResponseEvent deleteCarrier(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String carrierId = request.getPathParameters().get("carrierId");
        logger.log("Deleting carrier: " + carrierId);

        try (Connection conn = DatabaseUtil.getConnection()) {
            // Əvvəlcə əlaqəli status_updates-ləri sil
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM status_updates WHERE carrier_id = ?")) {
                ps.setString(1, carrierId);
                ps.executeUpdate();
            }

            // Sonra carrier-i sil
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM carriers WHERE carrier_id = ?")) {
                ps.setString(1, carrierId);
                ps.executeUpdate();
            }

            logger.log("Carrier deleted successfully");
            return buildResponse(200, "{\"message\":\"Carrier deleted successfully\"}");
        }
    }

    // ======================== STATUS UPDATES ========================

    private APIGatewayProxyResponseEvent createStatusUpdate(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
        logger.log("Creating status update for shipment: " + body.get("shipment_id").getAsString());

        String sql = "INSERT INTO status_updates (shipment_id, carrier_id, status, location, notes, \"timestamp\") " +
                "VALUES (?, ?, ?::StatusType, ?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, body.get("shipment_id").getAsString());
            ps.setString(2, body.get("carrier_id").getAsString());
            ps.setString(3, body.get("status").getAsString());
            ps.setString(4, body.get("location").getAsString());
            ps.setString(5, body.get("notes").getAsString());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();

            logger.log("Status update created successfully");
            return buildResponse(201, "{\"message\":\"Status update created successfully\"}");
        }
    }

    private APIGatewayProxyResponseEvent getStatusUpdates(APIGatewayProxyRequestEvent request, LambdaLogger logger) throws Exception {
        String shipmentId = request.getPathParameters().get("shipmentId");
        logger.log("Getting status updates for shipment: " + shipmentId);

        String sql = "SELECT * FROM status_updates WHERE shipment_id = ? ORDER BY update_id";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, shipmentId);
            ResultSet rs = ps.executeQuery();

            List<Map<String, Object>> results = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("update_id", rs.getInt("update_id"));
                item.put("shipment_id", rs.getString("shipment_id"));
                item.put("carrier_id", rs.getString("carrier_id"));
                item.put("status", rs.getString("status"));
                item.put("location", rs.getString("location"));
                item.put("notes", rs.getString("notes"));

                Timestamp ts = rs.getTimestamp("timestamp");
                if (ts != null) {
                    item.put("timestamp", ts.toInstant().atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                } else {
                    item.put("timestamp", null);
                }

                results.add(item);
            }

            return buildResponse(200, gson.toJson(results));
        }
    }

    // ======================== HELPER ========================

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);
        response.setBody(body);
        return response;
    }
}