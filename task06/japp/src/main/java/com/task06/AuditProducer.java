package com.task06;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.DynamoDbTriggerEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
    lambdaName = "audit_producer",
    roleName = "audit_producer-role",
    timeout = 30,
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DynamoDbTriggerEventSource(
    targetTable = "Configuration",
    batchSize = 1
)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "table_name", value = "${target_table}"),
    @EnvironmentVariable(key = "region", value = "${region}")
})
public class AuditProducer implements RequestHandler<DynamodbEvent, Void> {

    private final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        String auditTable = System.getenv("table_name");

        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();

            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> newImage =
                record.getDynamodb().getNewImage();

            String itemKey = newImage.get("key").getS();
            String modificationTime = Instant.now().toString();
            String id = UUID.randomUUID().toString();

            Map<String, com.amazonaws.services.dynamodbv2.model.AttributeValue> auditItem = new HashMap<>();
            auditItem.put("id", new com.amazonaws.services.dynamodbv2.model.AttributeValue(id));
            auditItem.put("itemKey", new com.amazonaws.services.dynamodbv2.model.AttributeValue(itemKey));
            auditItem.put("modificationTime", new com.amazonaws.services.dynamodbv2.model.AttributeValue(modificationTime));

            if ("INSERT".equals(eventName)) {
                Map<String, com.amazonaws.services.dynamodbv2.model.AttributeValue> newValue = new HashMap<>();
                newValue.put("key", new com.amazonaws.services.dynamodbv2.model.AttributeValue(itemKey));
                newValue.put("value", new com.amazonaws.services.dynamodbv2.model.AttributeValue()
                    .withN(newImage.get("value").getN()));
                auditItem.put("newValue", new com.amazonaws.services.dynamodbv2.model.AttributeValue()
                    .withM(newValue));

            } else if ("MODIFY".equals(eventName)) {
                Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> oldImage =
                    record.getDynamodb().getOldImage();

                auditItem.put("updatedAttribute", new com.amazonaws.services.dynamodbv2.model.AttributeValue("value"));
                auditItem.put("oldValue", new com.amazonaws.services.dynamodbv2.model.AttributeValue()
                    .withN(oldImage.get("value").getN()));
                auditItem.put("newValue", new com.amazonaws.services.dynamodbv2.model.AttributeValue()
                    .withN(newImage.get("value").getN()));
            }

            dynamoDB.putItem(new PutItemRequest(auditTable, auditItem));
        }
        return null;
    }
}