package dev.jozefowicz.stacjait.mynotes.common;

import java.util.List;

public class ResponseNote {
    private final String noteId;
    private final String title;
    private final String text;
    private final long timestamp;
    private final NoteType type;
    private final Long size;
    private final List<String> labels;

    public ResponseNote(String noteId, String title, String text, long timestamp, NoteType type, Long size, List<String> labels) {
        this.noteId = noteId;
        this.title = title;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.size = size;
        this.labels = labels;
    }

    public String getNoteId() {
        return noteId;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public NoteType getType() {
        return type;
    }

    public Long getSize() {
        return size;
    }

    public List<String> getLabels() {
        return labels;
    }

}
