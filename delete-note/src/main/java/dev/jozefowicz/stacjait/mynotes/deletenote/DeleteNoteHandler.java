package dev.jozefowicz.stacjait.mynotes.deletenote;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import dev.jozefowicz.stacjait.mynotes.common.NoteType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.HashMap;
import java.util.Map;

import static dev.jozefowicz.stacjait.mynotes.common.APIGatewayProxyResponseEventBuilder.response;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class DeleteNoteHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final static String BUCKET_NAME = System.getenv("BUCKET_NAME");
    private final static String TABLE_NAME = System.getenv("TABLE_NAME");

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final S3Client s3Client = S3Client.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {
            if (!request.getHttpMethod().equalsIgnoreCase("delete")) {
                return response(405, null);
            } else if (isNull(request.getRequestContext().getAuthorizer()) || request.getRequestContext().getAuthorizer().isEmpty()) {
                context.getLogger().log("Authorizer not configured");
                return response(401, null);
            }

            Map<String, String> claims = (Map<String, String>) request.getRequestContext().getAuthorizer().get("claims");
            final String userId = claims.get("cognito:username");

            if (nonNull(request.getPathParameters()) && request.getPathParameters().containsKey("id")) {
                Map<String, AttributeValue> params = new HashMap<>();
                params.put("userId", AttributeValue.builder().s(userId).build());
                params.put("noteId", AttributeValue.builder().s(request.getPathParameters().get("id")).build());
                final DeleteItemResponse deletedNote = dynamoDbClient.deleteItem(DeleteItemRequest
                        .builder()
                        .tableName(TABLE_NAME)
                        .key(params)
                        .returnValues(ReturnValue.ALL_OLD)
                        .build());

                if (!deletedNote.attributes().isEmpty() && deletedNote.attributes().containsKey("type") && NoteType.valueOf(deletedNote.attributes().get("type").s()).isStored()) {
                    s3Client.deleteObject(DeleteObjectRequest
                            .builder()
                            .bucket(BUCKET_NAME)
                            .key(deletedNote.attributes().get("s3Location").s())
                            .build());
                }
                return response(200, null);
            }

            return response(400, null);
        } catch (Exception e) {
            context.getLogger().log("Exception while processing request");
            e.printStackTrace();
            return response(500, null);
        }
    }


}
