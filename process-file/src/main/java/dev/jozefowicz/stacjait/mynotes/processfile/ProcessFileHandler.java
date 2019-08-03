package dev.jozefowicz.stacjait.mynotes.processfile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.event.S3EventNotification;
import dev.jozefowicz.stacjait.mynotes.common.NoteType;
import dev.jozefowicz.stacjait.mynotes.common.PersistedNote;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.Label;
import software.amazon.awssdk.services.rekognition.model.S3Object;

import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProcessFileHandler implements RequestHandler<S3EventNotification, Void> {

    private final static String TABLE_NAME = System.getenv("TABLE_NAME");

    private final RekognitionClient rekognitionClient = RekognitionClient.create();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();

    @Override
    public Void handleRequest(S3EventNotification event, Context context) {
        try {
            for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
                final String key = URLDecoder.decode(record.getS3().getObject().getKey(), "UTF-8");
                final String[] idFileName = key.split("/");
                final NoteType type = idFileName[2].matches("(.*/)*.+\\.(png|jpg|gif|bmp|jpeg|PNG|JPG|GIF|BMP)$") ? NoteType.IMAGE : NoteType.FILE;
                final List<String> labels = type == NoteType.IMAGE ? analyze(key, record.getS3().getBucket().getName()) : Collections.emptyList();
                final PersistedNote note = PersistedNote.file(idFileName[0], idFileName[1], idFileName[2], key, record.getS3().getObject().getSize(), type, labels);
                persist(note);
            }
            return null;
        } catch (Exception e) {
            context.getLogger().log("Exception while processing S3 event");
            e.printStackTrace();
            return null;
        }
    }

    private void persist(PersistedNote note) {
        dynamoDbClient.putItem(PutItemRequest
                .builder()
                .tableName(TABLE_NAME)
                .item(mapToItem(note))
                .build());
    }

    private List<String> analyze(String s3Location, String bucketName) {
        S3Object s3Object = S3Object.builder().bucket(bucketName).name(s3Location).build();
        Image build = Image.builder().s3Object(s3Object).build();
        DetectLabelsResponse detectLabelsResponse = rekognitionClient.detectLabels(DetectLabelsRequest
                .builder()
                .maxLabels(10)
                .minConfidence(75f)
                .image(build)
                .build());
        return detectLabelsResponse
                .labels()
                .stream()
                .map(Label::name)
                .collect(Collectors.toList());
    }

    private Map<String, AttributeValue> mapToItem(PersistedNote note) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(note.getUserId()).build());
        item.put("noteId", AttributeValue.builder().s(note.getNoteId()).build());
        item.put("title", AttributeValue.builder().s(note.getTitle()).build());
        item.put("type", AttributeValue.builder().s(note.getType().name()).build());
        item.put("size", AttributeValue.builder().n(Long.toString(note.getSize())).build());
        item.put("s3Location", AttributeValue.builder().s(note.getS3Location()).build());
        if (!note.getLabels().isEmpty()) {
            item.put("labels", AttributeValue.builder().ss(note.getLabels()).build());
        }
        item.put("timestamp", AttributeValue.builder().n(Long.toString(note.getTimestamp())).build());
        return item;
    }

}
