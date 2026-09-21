package com.example.batch;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * RUNNABLE STUB — a test double for the AWS Blu Age / Gapwalk runtime.
 *
 * It honors the EXACT SAME contract the Step Functions state machine depends on
 * (the licensed Gapwalk image honors the same one):
 *   - selected by the ECS Command override  -> args[0] = program name
 *   - reads env S3_BUCKET, S3_PREFIX (testRunPrefix), AWS_REGION
 *   - reads inputs from S3, writes outputs to S3 (overwrite = idempotent)
 *   - exits 0 on success, non-zero on failure
 *
 * It exists ONLY so the CloudFormation + Step Functions sample runs end-to-end in
 * a sandbox. It proves the ORCHESTRATION, not the modernization. The real business
 * logic lives in your transformed programs (see ../programs/Job1.java).
 */
public class BatchStub {

    private static String bucket;
    private static String prefix;      // testRunPrefix, e.g. "test-runs/abc/" or ""
    private static S3Client s3;

    public static void main(String[] args) {
        String program = args.length > 0 ? args[0] : "JOB1";
        bucket = System.getenv("S3_BUCKET");
        prefix = System.getenv().getOrDefault("S3_PREFIX", "");
        String region = System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2");
        software.amazon.awssdk.services.s3.S3ClientBuilder b = S3Client.builder().region(Region.of(region));
        // AWS_ENDPOINT_URL: point at LocalStack/MinIO for local testing (path-style).
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            b = b.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        s3 = b.build();

        System.out.printf("[stub] program=%s bucket=%s prefix='%s'%n", program, bucket, prefix);
        try {
            int rc;
            switch (program) {
                case "JOB1":  rc = job1();  break;
                case "JOB2A": rc = job2a(); break;
                case "JOB2B": rc = job2b(); break;
                case "JOB3":  rc = job3();  break;
                default:
                    System.err.println("[stub] unknown program: " + program);
                    rc = 2;
            }
            System.out.printf("[stub] %s exit=%d%n", program, rc);
            System.exit(rc);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);   // non-zero -> Step Functions routes to BatchJobExecutionFailed
        }
    }

    // JOB1: normalize the daily transactions extract.
    private static int job1() {
        List<String> in = readCsv("inbound/transactions/");         // txns.YYYYMMDD.csv
        // ─── REPLACE: real normalization ───────────────────────────────────
        // Drop the CSV header row so it isn't carried downstream as a data record.
        List<String> out = in.stream()
                .filter(r -> !r.startsWith("account_id,"))
                .map(String::trim)
                .collect(Collectors.toList());
        writeObject("processing/JOB1/output/normalized.csv", String.join("\n", out));
        return 0;
    }

    // JOB2A (parallel): compute fees from the normalized file.
    private static int job2a() {
        List<String> in = readCsv("processing/JOB1/output/");
        List<String> out = in.stream().map(r -> r + ",FEE").collect(Collectors.toList());
        writeObject("processing/JOB2A/output/fees.csv", String.join("\n", out));
        return 0;
    }

    // JOB2B (parallel): compute rebates from the normalized file.
    private static int job2b() {
        List<String> in = readCsv("processing/JOB1/output/");
        List<String> out = in.stream().map(r -> r + ",REBATE").collect(Collectors.toList());
        writeObject("processing/JOB2B/output/rebates.csv", String.join("\n", out));
        return 0;
    }

    // JOB3 (merge): combine fees + rebates into the outbound summary.
    private static int job3() {
        List<String> fees = readCsv("processing/JOB2A/output/");
        List<String> rebates = readCsv("processing/JOB2B/output/");
        List<String> out = new ArrayList<>();
        out.add("# summary: " + fees.size() + " fee rows, " + rebates.size() + " rebate rows");
        out.addAll(fees);
        out.addAll(rebates);
        writeObject("outbound/summary.csv", String.join("\n", out));
        return 0;
    }

    // --- S3 helpers: every key is prefixed with testRunPrefix when set --------

    private static String key(String rel) {
        return prefix.isEmpty() ? rel : prefix.replaceAll("/$", "") + "/" + rel;
    }

    /** Read and concatenate the lines of every object under a prefix. */
    private static List<String> readCsv(String relPrefix) {
        String p = key(relPrefix);
        List<String> lines = new ArrayList<>();
        ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(bucket).prefix(p).build();
        for (S3Object o : s3.listObjectsV2(req).contents()) {
            if (o.key().endsWith("/")) continue;
            ResponseBytes<?> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(o.key()).build());
            String body = new String(bytes.asByteArray(), StandardCharsets.UTF_8);
            for (String line : body.split("\n")) {
                if (!line.isBlank()) lines.add(line);
            }
        }
        System.out.printf("[stub] read %d rows from s3://%s/%s%n", lines.size(), bucket, p);
        return lines;
    }

    private static void writeObject(String rel, String body) {
        String k = key(rel);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(k).build(),
                RequestBody.fromString(body, StandardCharsets.UTF_8));
        System.out.printf("[stub] wrote s3://%s/%s%n", bucket, k);
    }
}
