#!/usr/bin/env python3
"""Read-only local context client. Use a scoped agent credential, never owner credentials."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

import context_store as C

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise C.ContextError("Context requests must not redirect credentials.")

def retrieve(operation, value=None, project=None, port=8766, token=None):
    if type(port) is not int or not 1 <= port <= 65535:
        raise C.ContextError("Invalid loopback port.")
    token = token or os.environ.get("CONTINUITY_AGENT_TOKEN")
    if not isinstance(token, str) or len(token) < 32:
        raise C.ContextError("A scoped agent token is required.")
    if operation == "search":
        query = {"q": value, "limit": 20}
        if project is not None: query["project_id"] = project
        path = "/v1/search?" + urllib.parse.urlencode(query)
    elif operation in {"project", "source"}:
        if not isinstance(value, str) or not C._id(value):
            raise C.ContextError("Use a stable project/source ID.")
        path = ("/v1/projects/" if operation == "project" else "/v1/sources/") + urllib.parse.quote(value, safe="")
    else:
        raise C.ContextError("Only search, project and source reads are supported.")
    req = urllib.request.Request(f"http://127.0.0.1:{port}" + path,
                                 headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=10) as response:
        raw = response.read(128_001)
        if len(raw) > 128_000:
            raise C.ContextError("Context response exceeded the bounded packet limit.")
        return C.parse(raw)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("operation", choices=["search", "project", "source"]); p.add_argument("value")
    p.add_argument("--project"); p.add_argument("--port", type=int, default=8766)
    a = p.parse_args()
    try:
        print(json.dumps(retrieve(a.operation, a.value, a.project, a.port), ensure_ascii=False, indent=2)); return 0
    except (C.ContextError, OSError, urllib.error.URLError) as exc:
        # HTTP error responses can contain private metadata; print only the error category.
        print("Context read failed: " + type(exc).__name__, file=sys.stderr); return 2

if __name__ == "__main__": raise SystemExit(main())
