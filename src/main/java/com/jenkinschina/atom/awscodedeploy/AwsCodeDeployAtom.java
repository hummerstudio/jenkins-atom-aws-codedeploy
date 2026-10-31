package com.jenkinschina.atom.awscodedeploy;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts a CodeDeploy deployment and, if asked, waits for it to finish.
 *
 * <p>The aws-java-sdk-codedeploy plugin does the same thing through the SDK. Here it is one signed JSON request plus
 * polling, which keeps the atom small and makes the failure modes explicit: an AWS error code is reported as AWS
 * phrased it, and a deployment that ends in FAILED says which one and why.
 */
public class AwsCodeDeployAtom implements AtomTask {

    private static final String SERVICE = "codedeploy";
    private static final String TARGET_PREFIX = "CodeDeploy_20141006.";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String region = context.getParam("region", "").trim();
        String accessKey = secret(context, "accessKey");
        String secretKey = secret(context, "secretKey");
        String sessionToken = secret(context, "sessionToken");
        String application = context.getParam("applicationName", "").trim();
        String group = context.getParam("deploymentGroup", "").trim();
        String bucket = context.getParam("s3Bucket", "").trim();
        String key = context.getParam("s3Key", "").trim();
        String bundleType = context.getParam("bundleType", "zip").trim();
        String endpointOverride = context.getParam("endpoint", "").trim();
        String description = context.getParam("description", "deployed by a Jenkins atom");
        boolean wait = context.getBooleanParam("wait", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 60), 600));
        int waitSeconds = Math.max(10, Math.min(context.getIntParam("waitTimeoutSeconds", 900), 7200));

        if (region.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "region is required");
        }
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "accessKey and secretKey are required (use a credential)");
        }
        if (application.isEmpty() || group.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "applicationName and deploymentGroup are required");
        }
        if (bucket.isEmpty() || key.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40004, "s3Bucket and s3Key are required (the revision to deploy)");
        }

        String endpoint = endpointOverride.isEmpty()
                ? "https://" + SERVICE + "." + region + ".amazonaws.com"
                : endpointOverride.replaceAll("/+$", "");
        String host = URI.create(endpoint).getAuthority();

        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("revisionType", "S3");
        revision.put("s3Location", Map.of(
                "bucket", bucket,
                "key", key,
                "bundleType", bundleType));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationName", application);
        body.put("deploymentGroupName", group);
        body.put("deploymentConfigName", context.getParam("deploymentConfig", "CodeDeployDefault.OneAtATime"));
        body.put("description", description);
        body.put("revision", revision);
        String payload = Json.write(body);

        AwsSigner signer = new AwsSigner(accessKey, secretKey, sessionToken, region, SERVICE);
        Map<String, String> signed = Map.of(
                "content-type", "application/x-amz-json-1.1",
                "x-amz-target", TARGET_PREFIX + "CreateDeployment");
        Map<String, String> headers = signer.headers("POST", "/", "", payload, host, signed);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        headers.forEach(request::header);

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            ErrorType type = response.statusCode() == 400 || response.statusCode() == 403 ? ErrorType.USER
                    : ErrorType.THIRD_PART;
            return AtomResult.error(type, 40005, "CreateDeployment was refused: HTTP " + response.statusCode()
                    + (message.isEmpty() ? "" : " " + truncate(message)));
        }

        String deploymentId = text(asMap(response.body()), "deploymentId");
        if (deploymentId.isEmpty()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40006,
                    "CreateDeployment returned no deploymentId: " + truncate(response.body()));
        }
        context.log("[codedeploy] started " + deploymentId + " for " + application + "/" + group);

        if (!wait) {
            return AtomResult.success()
                    .data("deploymentId", deploymentId)
                    .data("status", "started")
                    .data("applicationName", application)
                    .data("deploymentGroup", group);
        }

        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        String status = "Created";
        String summary = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5000);
            Result polled = poll(client, signer, endpoint, host, deploymentId, timeoutSeconds);
            if (polled.error != null) {
                return AtomResult.error(ErrorType.THIRD_PART, 40007, polled.error)
                        .data("deploymentId", deploymentId);
            }
            status = polled.status;
            summary = polled.summary;
            context.log("[codedeploy] " + deploymentId + " is " + status);
            if (!"Created".equals(status) && !"Queued".equals(status) && !"InProgress".equals(status)) {
                break;
            }
        }

        boolean succeeded = "Succeeded".equals(status);
        AtomResult result = succeeded ? AtomResult.success()
                : AtomResult.error(ErrorType.THIRD_PART, 40008,
                        "deployment " + deploymentId + " ended as " + status
                                + (summary.isEmpty() ? "" : ": " + summary));
        return result
                .data("deploymentId", deploymentId)
                .data("status", status)
                .data("applicationName", application)
                .data("deploymentGroup", group);
    }

    /** One GetDeploymentInfo call: enough to see whether the deployment is still running. */
    private Result poll(HttpClient client, AwsSigner signer, String endpoint, String host, String deploymentId,
                        int timeoutSeconds) throws Exception {
        String payload = Json.write(Map.of("deploymentId", deploymentId));
        Map<String, String> signed = Map.of(
                "content-type", "application/x-amz-json-1.1",
                "x-amz-target", TARGET_PREFIX + "GetDeployment");
        Map<String, String> headers = signer.headers("POST", "/", "", payload, host, signed);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new Result("unknown", "", "GetDeployment failed: HTTP " + response.statusCode()
                    + " " + truncate(response.body()));
        }
        Map<?, ?> info = asMap(response.body());
        Object infoNode = info.get("deploymentInfo");
        if (!(infoNode instanceof Map<?, ?> deployment)) {
            return new Result("unknown", "", "GetDeployment returned no deploymentInfo");
        }
        String status = text(deployment, "status");
        String summary = "";
        if (deployment.get("deploymentOverview") instanceof Map<?, ?> overview) {
            summary = "failedInstances=" + text(overview, "Failed") + ", succeededInstances=" + text(overview, "Succeeded");
        }
        return new Result(status, summary, null);
    }

    private record Result(String status, String summary, String error) {}

    private static String secret(AtomContext context, String name) {
        String value = context.getSecret(name);
        if (value == null || value.isBlank()) {
            value = context.getParam(name, "");
        }
        return value == null ? "" : value.trim();
    }

    private static Map<?, ?> asMap(String body) {
        try {
            Object parsed = Json.parse(body == null ? "{}" : body);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }
}
