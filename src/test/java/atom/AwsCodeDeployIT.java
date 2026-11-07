package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The aws-codedeploy atom against {@code tools/fake_aws.py}, which verifies SigV4 itself.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made: a green run proves the signing chain is right, the
 * payload carries the revision, the poll sees InProgress before Succeeded, a bad signature is refused, {@code wait=false}
 * returns at once, a deployment that ends FAILED fails the step, and a missing bucket is refused before the call.
 */
class AwsCodeDeployIT {

    private static final String ACCESS = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a signed deployment is started, polled to Succeeded, and the payload carries the revision")
    void deploysToSucceeded() throws Exception {
        try (FakeAws aws = FakeAws.start()) {
            AtomResult run = aws.run(atom, Map.of());

            run.assertOk();
            assertEquals("d-ABCDEF123", run.field("deploymentId"));
            assertEquals("Succeeded", run.field("status"));
            assertTrue(run.console().contains("is InProgress"),
                    "the poll should have seen InProgress first\nconsole:\n" + run.console());
            assertTrue(run.console().contains("is Succeeded"),
                    "the poll should have seen Succeeded\nconsole:\n" + run.console());

            List<Map<String, Object>> entries = aws.requests();
            for (Map<String, Object> entry : entries) {
                assertEquals(Boolean.TRUE, entry.get("ok"), "the endpoint refused a request: " + entry);
            }
            Map<String, Object> create = aws.single(entries, "CreateDeployment");
            Map<String, Object> body = Json.parseObject(String.valueOf(create.get("body")));
            assertEquals("acme-api", body.get("applicationName"), "body: " + body);
            assertEquals("prod", body.get("deploymentGroupName"), "body: " + body);
            Map<?, ?> revision = (Map<?, ?>) body.get("revision");
            Map<?, ?> location = (Map<?, ?>) revision.get("s3Location");
            assertEquals("acme-releases", location.get("bucket"), "body: " + body);
            assertEquals("zip", location.get("bundleType"), "body: " + body);
            assertNotNull(aws.single(entries, "GetDeployment"), "no polling happened");
        }
    }

    @Test
    @DisplayName("the endpoint does verify: a wrong secret is refused, and AWS's message says why")
    void refusesABadSignature() throws Exception {
        try (FakeAws aws = FakeAws.start()) {
            AtomResult run = aws.run(atom, Map.of("secretKey", "this-is-not-the-secret"));

            run.assertRefused();
            assertTrue(run.file("output.json").contains("InvalidSignatureException"),
                    "AWS's error should surface:\n" + run.file("output.json"));
            assertEquals("1", run.field("errorType"), "a refused request is a USER error");
        }
    }

    @Test
    @DisplayName("wait=false returns straight after the call")
    void returnsImmediatelyWithoutWait() throws Exception {
        try (FakeAws aws = FakeAws.start()) {
            AtomResult run = aws.run(atom, Map.of("wait", "false"));

            run.assertOk();
            assertEquals("started", run.field("status"), "wait=false should report 'started'");
        }
    }

    @Test
    @DisplayName("a deployment that ends in FAILED fails the step, with the counts")
    void failsWhenTheDeploymentFails() throws Exception {
        try (FakeAws aws = FakeAws.start("--fail-deployment")) {
            AtomResult run = aws.run(atom, Map.of());

            run.assertRefused();
            assertTrue(run.file("output.json").contains("ended as FAILED"),
                    "message:\n" + run.file("output.json"));
            assertTrue(run.file("output.json").contains("failedInstances=1"),
                    "the overview should be quoted:\n" + run.file("output.json"));
            assertEquals("FAILED", run.field("status"));
        }
    }

    @Test
    @DisplayName("a missing bucket is refused before anything is sent")
    void refusesBadInput() throws Exception {
        try (FakeAws aws = FakeAws.start()) {
            AtomResult run = aws.run(atom, Map.of("s3Bucket", ""));

            run.assertRefused();
            assertTrue(run.file("output.json").contains("s3Bucket and s3Key are required"),
                    "message:\n" + run.file("output.json"));
        }
    }

    /** The fake endpoint from {@code tools/fake_aws.py}, started per scenario and stopped afterwards. */
    private static final class FakeAws implements AutoCloseable {

        private final Process process;
        private final Path log;
        private final int port;

        private FakeAws(Process process, Path log, int port) {
            this.process = process;
            this.log = log;
            this.port = port;
        }

        static FakeAws start(String... flags) throws Exception {
            Path script = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")))
                    .resolve("tools").resolve("fake_aws.py");
            assertTrue(Files.isRegularFile(script), "missing " + script);
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            Path log = Files.createTempFile("fake-aws-", ".jsonl");
            List<String> command = new ArrayList<>(List.of("python3", script.toString(),
                    String.valueOf(port), log.toString(), ACCESS, SECRET));
            command.addAll(List.of(flags));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            FakeAws server = new FakeAws(process, log, port);
            server.awaitListening();
            return server;
        }

        private void awaitListening() throws Exception {
            for (int i = 0; i < 100; i++) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("fake_aws.py exited with " + process.exitValue());
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    return;
                } catch (IOException notYet) {
                    Thread.sleep(100);
                }
            }
            throw new IllegalStateException("fake_aws.py did not listen on " + port);
        }

        /** The inputs {@code smoke.sh}'s write_input wrote, with the scenario's overrides on top. */
        AtomResult run(Atom atom, Map<String, String> overrides) throws Exception {
            Map<String, String> doc = new LinkedHashMap<>();
            doc.put("region", "us-east-1");
            doc.put("applicationName", "acme-api");
            doc.put("deploymentGroup", "prod");
            doc.put("s3Bucket", "acme-releases");
            doc.put("s3Key", "acme-api/1.2.3.zip");
            doc.put("endpoint", "http://127.0.0.1:" + port);
            doc.put("waitTimeoutSeconds", "60");
            doc.putAll(overrides);
            String secret = doc.containsKey("secretKey") ? doc.get("secretKey") : SECRET;
            return atom.file(".secrets.json", Json.write(Map.of("accessKey", ACCESS, "secretKey", secret)))
                    .input(Json.write(doc))
                    .run();
        }

        List<Map<String, Object>> requests() throws IOException {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(Json.parseObject(line));
                }
            }
            assertFalse(entries.isEmpty(), "the endpoint saw no request");
            return entries;
        }

        Map<String, Object> single(List<Map<String, Object>> entries, String target) {
            List<Map<String, Object>> matching = entries.stream()
                    .filter(e -> String.valueOf(e.get("target")).endsWith(target))
                    .toList();
            assertFalse(matching.isEmpty(), "no " + target + " in " + entries);
            return matching.get(0);
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }
}
