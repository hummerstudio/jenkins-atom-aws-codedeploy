#!/usr/bin/env python3
"""A CodeDeploy-shaped endpoint that verifies SigV4 for real.

    fake_aws.py <port> <log> <access-key> <secret-key> [--fail-deployment] [--no-wait-steps]
"""
import datetime
import hashlib
import hmac
import http.server
import json
import sys
import urllib.parse

port, log_path, access_key, secret_key = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
flags = sys.argv[5:]
fail_deployment = "--fail-deployment" in flags
polls = {"count": 0}


def sign(key, msg):
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def expected_signature(headers, body, signed_headers, amz_date, scope):
    canonical_headers = ""
    for name in signed_headers.split(";"):
        value = headers.get(name, "")
        canonical_headers += f"{name}:{value.strip()}\n"
    payload_hash = hashlib.sha256(body).hexdigest()
    canonical_request = "\n".join(["POST", "/", "", canonical_headers, signed_headers, payload_hash])
    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, scope,
        hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()])
    date_stamp, region, service, _ = scope.split("/")
    key = sign(("AWS4" + secret_key).encode("utf-8"), date_stamp)
    key = sign(key, region)
    key = sign(key, service)
    key = sign(key, "aws4_request")
    return hmac.new(key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()


def check_auth(handler, body):
    auth = handler.headers.get("Authorization", "")
    if not auth.startswith("AWS4-HMAC-SHA256 "):
        return "missing Authorization"
    parts = dict(piece.strip().split("=", 1) for piece in auth[len("AWS4-HMAC-SHA256 "):].split(","))
    credential = parts.get("Credential", "")
    signed_headers = parts.get("SignedHeaders", "")
    signature = parts.get("Signature", "")
    try:
        key_id, date_stamp, region, service, terminator = credential.split("/")
    except ValueError:
        return "malformed Credential"
    if key_id != access_key:
        return "unknown access key"
    scope = "/".join([date_stamp, region, service, terminator])
    amz_date = handler.headers.get("X-Amz-Date", "")
    if not amz_date.startswith(date_stamp):
        return "X-Amz-Date does not match the credential scope"
    wanted = expected_signature(handler.headers, body, signed_headers, amz_date, scope)
    if not hmac.compare_digest(wanted, signature):
        return "signature mismatch"
    return None


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        target = self.headers.get("X-Amz-Target", "")
        problem = check_auth(self, body)
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"target": target, "ok": problem is None, "problem": problem,
                                  "body": body.decode("utf-8", "replace")}) + "\n")
        if problem is not None:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(json.dumps({"__type": "InvalidSignatureException", "message": problem}).encode())
            return

        if target.endswith("CreateDeployment"):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps({"deploymentId": "d-ABCDEF123"}).encode())
            return
        if target.endswith("GetDeployment"):
            polls["count"] += 1
            if polls["count"] < 2:
                status = "InProgress"
            else:
                status = "FAILED" if fail_deployment else "Succeeded"
            payload = {"deploymentInfo": {
                "status": status,
                "deploymentOverview": {"Failed": "1" if status == "FAILED" else "0", "Succeeded": "1"},
            }}
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps(payload).encode())
            return
        self.send_response(400)
        self.end_headers()
        self.wfile.write(json.dumps({"__type": "InvalidAction", "message": target}).encode())

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
