package com.mettyoung.creditcardapplication.document.internal;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The object store through the S3 API. The only class in the codebase that knows the SDK exists — which is
 * why swapping LocalStack for MinIO or for AWS is configuration, not a code change.
 */
@Component
class S3ObjectStoreAdapter implements ObjectStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStoreAdapter(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public PresignedUpload presignUpload(ObjectKey key, Constraints constraints, Duration validFor) {
        // Content-Length and the checksum are signed into the URL, so the store refuses bytes that are the
        // wrong length or hash before they are ever stored. Our own /complete check is the second line.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .contentType(constraints.contentType().mediaType())
                .contentLength(constraints.sizeBytes())
                .checksumSHA256(constraints.sha256Base64())
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .putObjectRequest(put)
                        .build());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", constraints.contentType().mediaType());
        headers.put("x-amz-checksum-sha256", constraints.sha256Base64());
        return new PresignedUpload(URI.create(presigned.url().toString()), "PUT",
                Map.copyOf(headers), presigned.expiration());
    }

    @Override
    public Optional<StoredObject> head(ObjectKey key) {
        try {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return Optional.of(new StoredObject(response.contentLength(), response.checksumSHA256()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> readHead(ObjectKey key, int count) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                // Only the first bytes: the magic-byte check needs no more, and a 10 MB object should
                // never be pulled into the application to answer it.
                .range("bytes=0-" + (count - 1))
                .build();
        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(get)) {
            return Optional.of(readUpTo(stream, count));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read object head for " + key.value(), e);
        }
    }

    @Override
    public void delete(ObjectKey key) {
        s3.deleteObject(builder -> builder.bucket(bucket).key(key.value()));
    }

    void createBucketIfMissing() {
        boolean exists = s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(bucket));
        if (!exists) {
            s3.createBucket(builder -> builder.bucket(bucket));
        }
    }

    /**
     * FR10: the web UI PUTs bytes straight to a pre-signed URL, which is on the store's origin, not the app's. A
     * browser preflights that request, so the bucket must allow PUT from the app's origin. Nothing else: no GET,
     * no wildcard origin.
     * <p>
     * Headers are a wildcard rather than the two the URL is signed with. LocalStack splits a preflight's
     * Access-Control-Request-Headers only on ", ", while browsers send them as "a,b" with no space, so naming the
     * two refuses every real browser upload in development. The wildcard costs nothing here: the signature
     * already fixes which headers, and which values, the store accepts.
     */
    void allowBrowserUploads(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return;
        }
        s3.putBucketCors(builder -> builder.bucket(bucket).corsConfiguration(cors -> cors.corsRules(
                CORSRule.builder()
                        .allowedOrigins(origins)
                        .allowedMethods("PUT")
                        .allowedHeaders("*")
                        .maxAgeSeconds(3600)
                        .build())));
    }

    private static byte[] readUpTo(InputStream stream, int count) throws IOException {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int n = stream.read(buffer, read, count - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (read == count) {
            return buffer;
        }
        byte[] exact = new byte[read];
        System.arraycopy(buffer, 0, exact, 0, read);
        return exact;
    }
}
