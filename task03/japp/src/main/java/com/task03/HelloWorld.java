package com.task03;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
    lambdaName = "hello_world",
    roleName = "hello_world-role",
	timeout = 30,
	memory = 512,
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class HelloWorld implements RequestHandler<Object, Map<String, Object>> {

    @Override
	public Map<String, Object> handleRequest(Object request, Context context) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", 200);
		response.put("body", "{\"statusCode\": 200, \"message\": \"Hello from Lambda\"}");
		response.put("headers", new HashMap<String, String>() {{
			put("Content-Type", "application/json");
		}});
		return response;
	}
}