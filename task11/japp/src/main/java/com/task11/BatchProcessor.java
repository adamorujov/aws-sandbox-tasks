package com.task11;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.S3EventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
        lambdaName = "batch_processor",
        roleName = "batch_processor-role",
        runtime = DeploymentRuntime.JAVA11,
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
        timeout = 300,
        memory = 512
)
@S3EventSource(
        targetBucket = "data-transfer-storage",
        events = {"s3:ObjectCreated:*"}
)
@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "DB_ENDPOINT", value = "${db_endpoint}"),
        @EnvironmentVariable(key = "MASTER_USER_SECRET_NAME", value = "${master_user_secret_name}"),
        @EnvironmentVariable(key = "DB_NAME", value = "${db_name}"),
        @EnvironmentVariable(key = "DB_PORT", value = "${db_port}"),
        @EnvironmentVariable(key = "REGION", value = "${region}")
})
public class BatchProcessor implements RequestHandler<S3Event, String> {

    private static final int BATCH_SIZE = 2000;

    @Override
    public String handleRequest(S3Event event, Context context) {
        LambdaLogger logger = context.getLogger();

        S3EventNotification.S3EventNotificationRecord record = event.getRecords().get(0);
        String bucket = record.getS3().getBucket().getName();
        String key = record.getS3().getObject().getKey();

        logger.log("Processing file: " + key + " from bucket: " + bucket);

        AmazonS3 s3Client = null;
        Connection conn = null;

        try {
            // SDK v1 S3 client
            s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(System.getenv("REGION"))
                    .build();

            // S3-dən faylı oxumaq
            S3Object s3Object = s3Client.getObject(bucket, key);
            InputStream inputStream = s3Object.getObjectContent();

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            // Header sətirini oxu və keç
            String header = reader.readLine();
            logger.log("CSV Header: " + header);

            // DB bağlantısı
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // Fayl adına görə müvafiq metodu çağır
            String fileName = key.toLowerCase();
            if (fileName.contains("shipments")) {
                processShipments(reader, conn, logger);
            } else if (fileName.contains("carriers")) {
                processCarriers(reader, conn, logger);
            } else if (fileName.contains("status_updates")) {
                processStatusUpdates(reader, conn, logger);
            } else {
                logger.log("Unknown file type: " + key + ". Skipping.");
                return "SKIPPED: Unknown file type";
            }

            conn.commit();
            logger.log("Transaction committed successfully for file: " + key);

            reader.close();
            inputStream.close();

            return "SUCCESS";

        } catch (Exception e) {
            logger.log("ERROR processing file " + key + ": " + e.getMessage());

            if (conn != null) {
                try {
                    conn.rollback();
                    logger.log("Transaction rolled back");
                } catch (Exception rollbackEx) {
                    logger.log("Rollback failed: " + rollbackEx.getMessage());
                }
            }

            return "ERROR: " + e.getMessage();

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception closeEx) {
                    logger.log("Connection close failed: " + closeEx.getMessage());
                }
            }
            // SDK v1 AmazonS3 client-in close() metodu yoxdur, GC idarə edir
        }
    }

    // ======================== SHIPMENTS ========================

    private void processShipments(BufferedReader reader, Connection conn, LambdaLogger logger) throws Exception {
        String sql = "INSERT INTO shipments (shipment_id, order_id, origin, destination, weight_kg, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (shipment_id) DO NOTHING";

        PreparedStatement ps = conn.prepareStatement(sql);
        String line;
        int count = 0;
        int errorCount = 0;

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;

            String[] fields = parseCsvLine(line);
            if (fields.length < 6) {
                errorCount++;
                continue;
            }

            try {
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setBigDecimal(5, new BigDecimal(fields[4].trim()));
                ps.setTimestamp(6, parseTimestamp(fields[5].trim()));
                ps.addBatch();
                count++;

                if (count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    ps.clearBatch();
                    logger.log("Shipments batch executed. Total so far: " + count);
                }
            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) {
                    logger.log("Error parsing shipment line: " + line + " | Error: " + e.getMessage());
                }
            }
        }

        if (count % BATCH_SIZE != 0) {
            ps.executeBatch();
        }

        ps.close();
        logger.log("Shipments processing complete. Total: " + count + ", Errors: " + errorCount);
    }

    // ======================== CARRIERS ========================

    private void processCarriers(BufferedReader reader, Connection conn, LambdaLogger logger) throws Exception {
        String sql = "INSERT INTO carriers (carrier_id, name, email, phone, is_active) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (carrier_id) DO NOTHING";

        PreparedStatement ps = conn.prepareStatement(sql);
        String line;
        int count = 0;
        int errorCount = 0;

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;

            String[] fields = parseCsvLine(line);
            if (fields.length < 5) {
                errorCount++;
                continue;
            }

            try {
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setBoolean(5, Boolean.parseBoolean(fields[4].trim()));
                ps.addBatch();
                count++;

                if (count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    ps.clearBatch();
                    logger.log("Carriers batch executed. Total so far: " + count);
                }
            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) {
                    logger.log("Error parsing carrier line: " + line + " | Error: " + e.getMessage());
                }
            }
        }

        if (count % BATCH_SIZE != 0) {
            ps.executeBatch();
        }

        ps.close();
        logger.log("Carriers processing complete. Total: " + count + ", Errors: " + errorCount);
    }

    // ======================== STATUS UPDATES ========================

    private void processStatusUpdates(BufferedReader reader, Connection conn, LambdaLogger logger) throws Exception {
        String sql = "INSERT INTO status_updates (shipment_id, carrier_id, status, location, notes, \"timestamp\") " +
                "VALUES (?, ?, ?::StatusType, ?, ?, ?)";

        PreparedStatement ps = conn.prepareStatement(sql);
        String line;
        int count = 0;
        int errorCount = 0;

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;

            String[] fields = parseCsvLine(line);
            if (fields.length < 6) {
                errorCount++;
                continue;
            }

            try {
                ps.setString(1, fields[0].trim());
                ps.setString(2, fields[1].trim());
                ps.setString(3, fields[2].trim());
                ps.setString(4, fields[3].trim());
                ps.setString(5, fields[4].trim());
                ps.setTimestamp(6, parseTimestamp(fields[5].trim()));
                ps.addBatch();
                count++;

                if (count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    ps.clearBatch();
                    logger.log("Status updates batch executed. Total so far: " + count);
                }
            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) {
                    logger.log("Error parsing status_update line: " + line + " | Error: " + e.getMessage());
                }
            }
        }

        if (count % BATCH_SIZE != 0) {
            ps.executeBatch();
        }

        ps.close();
        logger.log("Status updates processing complete. Total: " + count + ", Errors: " + errorCount);
    }

    // ======================== HELPERS ========================

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }

    private Timestamp parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return Timestamp.from(Instant.now());
        }

        try {
            Instant instant = Instant.parse(value);
            return Timestamp.from(instant);
        } catch (DateTimeParseException e1) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return Timestamp.from(ldt.toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException e2) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(value,
                            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"));
                    return Timestamp.from(ldt.toInstant(ZoneOffset.UTC));
                } catch (DateTimeParseException e3) {
                    return Timestamp.from(Instant.now());
                }
            }
        }
    }
}