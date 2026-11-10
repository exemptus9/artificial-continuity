import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import lexi_mcp


class LexiMcpTests(unittest.TestCase):
    def test_initialize_advertises_read_only_tools(self):
        response = lexi_mcp.dispatch({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        })
        self.assertEqual(response["result"]["serverInfo"]["name"], "artificial-continuity-lexi")
        self.assertEqual(response["result"]["capabilities"], {"tools": {"listChanged": False}})

    def test_tool_list_contains_only_read_tools(self):
        response = lexi_mcp.dispatch({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in response["result"]["tools"]}
        self.assertEqual(names, {"list_granted_projects", "get_project_context", "search_project_context"})

    @patch("lexi_mcp.context_client.urllib.request.build_opener")
    def test_project_catalog_uses_fixed_loopback_endpoint(self, build_opener):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, limit): return b'{"projects": []}'
        class Opener:
            def __init__(self): self.request = None; self.timeout = None
            def open(self, request, timeout):
                self.request, self.timeout = request, timeout
                return Response()
        opener = Opener()
        build_opener.return_value = opener
        result = lexi_mcp.context_client.retrieve("projects", token="x" * 32)
        self.assertEqual(result, {"projects": []})
        self.assertEqual(opener.request.full_url, "http://127.0.0.1:8766/v1/projects")
        self.assertEqual(opener.timeout, 10)

    @patch("lexi_mcp.context_client.retrieve", return_value={"projects": [{"id": "P1", "name": "Continuity"}]})
    def test_list_returns_only_service_granted_projects(self, retrieve):
        result = lexi_mcp._call_tool("list_granted_projects", {})
        self.assertFalse(result["isError"])
        retrieve.assert_called_once_with("projects", None, project=None)

    def test_list_granted_projects_rejects_arguments(self):
        result = lexi_mcp._call_tool("list_granted_projects", {"project_id": "P1"})
        self.assertTrue(result["isError"])

    @patch("lexi_mcp.context_client.retrieve", return_value={"project_id": "P1", "state": "CONTINUE"})
    def test_project_read_uses_exact_project_id(self, retrieve):
        result = lexi_mcp._call_tool("get_project_context", {"project_id": "P1"})
        self.assertFalse(result["isError"])
        retrieve.assert_called_once_with("project", "P1", project=None)

    @patch("lexi_mcp.context_client.retrieve", return_value={"matches": []})
    def test_search_is_always_project_filtered(self, retrieve):
        lexi_mcp._call_tool("search_project_context", {"project_id": "P1", "query": "next action"})
        retrieve.assert_called_once_with("search", "next action", project="P1")

    def test_rejects_extra_fields_and_write_tool_names(self):
        extra = lexi_mcp._call_tool("get_project_context", {"project_id": "P1", "write": True})
        write = lexi_mcp._call_tool("apply_update", {"project_id": "P1"})
        self.assertTrue(extra["isError"])
        self.assertTrue(write["isError"])

    def test_notifications_have_no_response(self):
        self.assertIsNone(lexi_mcp.dispatch({
            "jsonrpc": "2.0", "method": "notifications/initialized"
        }))


if __name__ == "__main__":
    unittest.main()
