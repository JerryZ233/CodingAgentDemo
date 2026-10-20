package com.demo.memory.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

final class MemoryJson {
    static final Gson GSON = new GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (value, type, context) -> Instant.parse(value.getAsString()))
            .create();
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String text(byte[] bytes) throws java.io.IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException malformed) { throw MemoryStorageException.malformed(malformed); }
    }
    static String version(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String version(String text) { return version(bytes(text)); }
    static <T> T decode(byte[] bytes, Class<T> type) throws java.io.IOException {
        try {
            T value = GSON.fromJson(text(bytes), type);
            if (value == null) throw new IllegalArgumentException("Missing object");
            return value;
        } catch (RuntimeException malformed) {
            for (Throwable cause = malformed; cause != null; cause = cause.getCause()) {
                if (cause instanceof MemoryStorageException storage) throw storage;
            }
            throw MemoryStorageException.malformed(malformed);
        }
    }
}
