package com.task05;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
    lambdaName = "api_handler",
    roleName = "api_handler-role",
    timeout = 30,
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "table_name", value = "${target_table}"),
    @EnvironmentVariable(key = "region", value = "${region}")
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

    @Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		String tableName = System.getenv("table_name");

		// Request body-ni parse et
		Map<String, Object> body;
		Object rawBody = event.get("body");
		if (rawBody instanceof String) {
			body = new com.google.gson.Gson().fromJson((String) rawBody, Map.class);
		} else {
			body = (Map<String, Object>) rawBody;
		}

		// Event data yarat
		String id = UUID.randomUUID().toString();
		String createdAt = Instant.now().toString();
		int principalId = ((Number) body.get("principalId")).intValue();
		Map<String, Object> content = (Map<String, Object>) body.get("content");

		// DynamoDB item yarat
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", new AttributeValue(id));
		item.put("principalId", new AttributeValue().withN(String.valueOf(principalId)));
		item.put("createdAt", new AttributeValue(createdAt));

		Map<String, AttributeValue> bodyMap = new HashMap<>();
		for (Map.Entry<String, Object> entry : content.entrySet()) {
			bodyMap.put(entry.getKey(), new AttributeValue(entry.getValue().toString()));
		}
		item.put("body", new AttributeValue().withM(bodyMap));

		// DynamoDB-ə yaz
		dynamoDB.putItem(new PutItemRequest(tableName, item));

		// Event response yarat
		Map<String, Object> eventResponse = new HashMap<>();
		eventResponse.put("id", id);
		eventResponse.put("principalId", principalId);
		eventResponse.put("createdAt", createdAt);
		eventResponse.put("body", content);

		// Response body yarat
		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("statusCode", 201);
		responseBody.put("event", eventResponse);

		// API Gateway proxy response formatı
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", 201);
		response.put("headers", new HashMap<String, String>() {{
			put("Content-Type", "application/json");
		}});
		response.put("body", new com.google.gson.Gson().toJson(responseBody));

		return response;
	}
}