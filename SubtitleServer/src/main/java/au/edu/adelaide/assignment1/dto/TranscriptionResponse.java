package au.edu.adelaide.assignment1.dto;

/**
 * TranscriptionResponse is a record that represents the response of a transcription request.
 * @param transcription The transcribed text from the audio input.
 */
public record TranscriptionResponse(
    String transcription
) {}
