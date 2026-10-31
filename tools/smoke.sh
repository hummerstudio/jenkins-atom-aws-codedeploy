#!/usr/bin/env bash
# The fake endpoint verifies SigV4 itself, so a green run proves the signing chain is right.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((30000 + RANDOM % 900))
access="AKIDEXAMPLE"; secret="wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
start() { python3 tools/fake_aws.py "$port" "$log" "$access" "$secret" "$@" & server=$!
  for _ in $(seq 1 40); do nc -z 127.0.0.1 "$port" 2>/dev/null && break; sleep 0.1; done; }
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

ws="$(mktemp -d)"
write_input() { # write_input [key=value ...]  (secretKey can be overridden with secret=...)
  python3 - "$ws/input.json" "$port" "$access" "$@" <<'PY'
import json, sys
path, port, access = sys.argv[1], sys.argv[2], sys.argv[3]
doc = {"region": "us-east-1", "applicationName": "acme-api", "deploymentGroup": "prod",
       "s3Bucket": "acme-releases", "s3Key": "acme-api/1.2.3.zip", "endpoint": "http://127.0.0.1:" + port,
       "waitTimeoutSeconds": "60"}
for pair in sys.argv[4:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
}
write_secret() { printf '{"accessKey": "%s", "secretKey": "%s"}' "$1" "$2" >"$ws/.secrets.json"; }

# --- a successful deployment: signed correctly, polled until it finishes
write_secret "$access" "$secret"
write_input
run "$ws" >"$ws/atom.log" 2>&1 || fail "deploy: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(output "$ws" deploymentId)" = "d-ABCDEF123" ] || fail "deployment id: $(cat "$ws/output.json")"
[ "$(output "$ws" status)" = "Succeeded" ] || fail "status: $(cat "$ws/output.json")"
grep -q "is InProgress" "$ws/atom.log" || fail "the poll should have seen InProgress first"
grep -q "is Succeeded" "$ws/atom.log" || fail "the poll should have seen Succeeded"

# the request really was signed over the right target and carried the revision
python3 - "$log" <<'PY' || fail "the payload or the target was wrong"
import json, sys
entries = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
assert all(e["ok"] for e in entries), entries
create = [e for e in entries if e["target"].endswith("CreateDeployment")][0]
body = json.loads(create["body"])
assert body["applicationName"] == "acme-api", body
assert body["deploymentGroupName"] == "prod", body
assert body["revision"]["s3Location"]["bucket"] == "acme-releases", body
assert body["revision"]["s3Location"]["bundleType"] == "zip", body
assert [e for e in entries if e["target"].endswith("GetDeployment")], "no polling happened"
PY

# --- the endpoint does verify: a wrong secret is refused, and the message says why
: >"$log"
write_secret "$access" "this-is-not-the-secret"
write_input
run "$ws" >"$ws/atom.log" 2>&1 && fail "a bad signature must fail"
grep -q "InvalidSignatureException" "$ws/output.json" || fail "AWS's error should surface: $(cat "$ws/output.json")"
grep -q '"errorType":1' "$ws/output.json" || fail "a refused request is a USER error"

# --- wait=false returns straight after the call
write_secret "$access" "$secret"
write_input wait=false
run "$ws" >"$ws/atom.log" 2>&1 || fail "wait=false: $(cat "$ws/output.json")"
[ "$(output "$ws" status)" = "started" ] || fail "wait=false should report 'started': $(cat "$ws/output.json")"

# --- a deployment that ends in FAILED is a failure of the step, with the counts
stop; start --fail-deployment
write_secret "$access" "$secret"
write_input
run "$ws" >"$ws/atom.log" 2>&1 && fail "a failed deployment must fail the step"
grep -q "ended as FAILED" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q "failedInstances=1" "$ws/output.json" || fail "the overview should be quoted: $(cat "$ws/output.json")"
[ "$(output "$ws" status)" = "FAILED" ] || fail "status output: $(cat "$ws/output.json")"

# --- bad input
stop; start
write_secret "$access" "$secret"
write_input s3Bucket=
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing bucket must be refused"
grep -q "s3Bucket and s3Key are required" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: real SigV4 verification, deployment payload, polling to Succeeded, bad signature refused, wait=false, FAILED deployment, bad input"
