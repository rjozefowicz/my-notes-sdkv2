package dev.jozefowicz.stacjait.mynotes.fileupload;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jozefowicz.stacjait.mynotes.common.PersistedNote;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.jozefowicz.stacjait.mynotes.common.APIGatewayProxyResponseEventBuilder.response;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * This AWS Lambda handler uses SDK v1 because there is still no implementation of presigned URL generation in SDK v2
 * <p>
 * https://github.com/aws/aws-sdk-java-v2/issues/203
 */
public class FileUploadHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final static String BUCKET_NAME = System.getenv("BUCKET_NAME");
    private final static String TABLE_NAME = System.getenv("TABLE_NAME");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AmazonDynamoDB dynamoDbClient = AmazonDynamoDBClient.builder().build();
    private final AmazonS3 amazonS3 = AmazonS3ClientBuilder.standard()
            .build();

    public FileUploadHandler() {
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            if (isNull(request.getRequestContext().getAuthorizer()) || request.getRequestContext().getAuthorizer().isEmpty()) {
                context.getLogger().log("Authorizer not configured");
                return response(401, null);
            }

            Map<String, String> claims = (Map<String, String>) request.getRequestContext().getAuthorizer().get("claims");
            final String userId = claims.get("cognito:username");

            switch (request.getHttpMethod().toUpperCase()) {
                case "GET":
                    return get(request, userId);
                case "POST":
                    return post(request, userId);
                default:
                    return response(405, null);
            }
        } catch (Exception e) {
            context.getLogger().log("Exception while processing request");
            e.printStackTrace();
            return response(500, null);
        }
    }

    private APIGatewayProxyResponseEvent post(APIGatewayProxyRequestEvent request, String userId) throws IOException {
        if (isNull(request.getBody())) {
            return response(400, null);
        }
        FileUploadRequest fileUploadRestest = this.objectMapper.readValue(request.getBody(), FileUploadRequest.class);
        if (isNull(fileUploadRestest.getName()) || fileUploadRestest.getName().isEmpty()) {
            return response(400, null);
        }
        final String key = userId + "/" + UUID.randomUUID().toString() + "/" + fileUploadRestest.getName();
        return response(200, this.objectMapper.writeValueAsString(SignedUrlResponse.of(presignedUrl(key, HttpMethod.PUT))));
    }

    private APIGatewayProxyResponseEvent get(APIGatewayProxyRequestEvent request, String userId) throws IOException {
        if (nonNull(request.getPathParameters()) && request.getPathParameters().containsKey("id")) {
            Map<String, String> attributeNames = new HashMap<>();
            attributeNames.put("#noteId", "noteId");
            attributeNames.put("#userId", "userId");
            Map<String, AttributeValue> attributeValues = new HashMap<>();
            attributeValues.put(":noteId", new AttributeValue().withS(request.getPathParameters().get("id")));
            attributeValues.put(":userId", new AttributeValue().withS(userId));
            final QueryResult queryResult = dynamoDbClient.query(
                    new QueryRequest()
                            .withTableName(TABLE_NAME)
                            .withKeyConditionExpression("#userId = :userId and #noteId = :noteId")
                            .withExpressionAttributeNames(attributeNames)
                            .withExpressionAttributeValues(attributeValues));
            if (queryResult.getCount().equals(1)) {
                final PersistedNote note = this.objectMapper.readValue(ItemUtils.toItem(queryResult.getItems().get(0)).toJSON(), PersistedNote.class);
                return response(200, this.objectMapper.writeValueAsString(SignedUrlResponse.of(presignedUrl(note.getS3Location(), HttpMethod.GET))));
            }
        }
        return response(400, null);
    }

    private String presignedUrl(String key, HttpMethod httpMethod) {
        return amazonS3.generatePresignedUrl(new GeneratePresignedUrlRequest(BUCKET_NAME, key, httpMethod)).toString();
    }
}
