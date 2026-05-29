package com.task08;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.RuleEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "uuid_generator",
    roleName = "uuid_generator-role",
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@RuleEventSource(
    targetRule = "uuid_trigger"
)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "target_bucket", value = "${target_bucket}")
})
public class UuidGenerator implements RequestHandler<Object, Map<String, Object>> {

    private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> handleRequest(Object request, Context context) {
        try {
            String bucketName = System.getenv("target_bucket");
            String fileName = Instant.now().toString();

            List<String> uuids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                uuids.add(UUID.randomUUID().toString());
            }

            Map<String, Object> content = new HashMap<>();
            content.put("ids", uuids);

            String jsonContent = objectMapper.writeValueAsString(content);
            s3Client.putObject(bucketName, fileName, jsonContent);

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("statusCode", 200);
            resultMap.put("body", "UUIDs saved successfully");
            return resultMap;

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("statusCode", 500);
            resultMap.put("body", "Error: " + e.getMessage());
            return resultMap;
        }
    }
}