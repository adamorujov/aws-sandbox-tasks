package com.task11;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.S3EventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.environment.ValueTransformer;


@LambdaHandler(
    lambdaName = "batch_processor",
    roleName = "batch_processor-role",
	runtime = DeploymentRuntime.JAVA11,
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
	subnetsIds = {"${lambda_sn_id}"},
    securityGroupIds = {"${logistic_sg_id}"},
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@S3EventSource(
    targetBucket = "data-transfer-storage",
    events = {"s3:ObjectCreated:*"}
)
@DependsOn(
       name = "data-transfer-storage",
       resourceType = ResourceType.S3_BUCKET
)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "REGION", value = "${region}"),
    @EnvironmentVariable(key = "DB_ENDPOINT", value = "logistic-cluster",
        valueTransformer = ValueTransformer.RDS_DB_CLUSTER_NAME_TO_ENDPOINT),
    @EnvironmentVariable(key = "MASTER_USER_SECRET_NAME", value = "logistic-cluster",
        valueTransformer = ValueTransformer.RDS_DB_CLUSTER_NAME_TO_MASTER_USER_SECRET_NAME)
})
public class BatchProcessor implements RequestHandler<S3Event, String> {

    private static final int BATCH_SIZE = 1000;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String handleRequest(S3Event event, Context context) {
        String region = System.getenv("REGION");

        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getKey();
            context.getLogger().log("Processing file: " + key + " from bucket: " + bucket);

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withRegion(region).build();
                S3Object s3Object = s3Client.getObject(bucket, key);
                BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object.getObjectContent()));

                if (key.contains("shipments.csv")) {
                    processShipments(conn, reader, context);
                } else if (key.contains("carriers.csv")) {
                    processCarriers(conn, reader, context);
                } else if (key.contains("status_updates.csv")) {
                    processStatusUpdates(conn, reader, context);
                } else {
                    context.getLogger().log("Unknown file: " + key);
                }

                conn.commit();
                context.getLogger().log("Successfully processed: " + key);
            } catch (Exception e) {
                context.getLogger().log("ERROR processing " + key + ": " + e.getMessage());
            }
        }
        return "Processing complete";
    }

    // ==================== PROCESSORS ====================

    private void processShipments(Connection conn, BufferedReader reader, Context context) throws Exception {
        String sql = "INSERT INTO shipments(shipment_id, order_id, origin, destination, weight_kg, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (shipment_id) DO NOTHING";
        reader.readLine(); // skip header
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 6) continue;
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setDouble(5, Double.parseDouble(fields[4].trim()));
                ps.setTimestamp(6, Timestamp.valueOf(fields[5].trim().replace("T", " ").substring(0, 23)));
                ps.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    context.getLogger().log("Shipments inserted: " + count);
                }
            }
            ps.executeBatch();
            context.getLogger().log("Total shipments inserted: " + count);
        }
    }

    private void processCarriers(Connection conn, BufferedReader reader, Context context) throws Exception {
        String sql = "INSERT INTO carriers(carrier_id, name, email, phone, is_active) " +
                     "VALUES (?, ?, ?, ?, ?) ON CONFLICT (carrier_id) DO NOTHING";
        reader.readLine(); // skip header
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 5) continue;
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setBoolean(5, Boolean.parseBoolean(fields[4].trim()));
                ps.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    context.getLogger().log("Carriers inserted: " + count);
                }
            }
            ps.executeBatch();
            context.getLogger().log("Total carriers inserted: " + count);
        }
    }

    private void processStatusUpdates(Connection conn, BufferedReader reader, Context context) throws Exception {
        String sql = "INSERT INTO status_updates(shipment_id, carrier_id, status, location, notes, timestamp) " +
                     "VALUES (?, ?, ?::StatusType, ?, ?, ?)";
        reader.readLine(); // skip header
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 6) continue;
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setString(5, fields[4].trim());
                ps.setTimestamp(6, Timestamp.valueOf(fields[5].trim().replace("T", " ").substring(0, 23)));
                ps.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    context.getLogger().log("Status updates inserted: " + count);
                }
            }
            ps.executeBatch();
            context.getLogger().log("Total status updates inserted: " + count);
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

    // ==================== CSV PARSER ====================

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
