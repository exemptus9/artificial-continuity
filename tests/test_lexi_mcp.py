import unittest
from unittest.mock import patch

import lexi_mcp


class LexiMcpTests(unittest.TestCase):
    def test_initialize_advertises_read_only_tools(self):
        response = lexi_mcp.dispatch({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        })
        self.assertEqual(response["result"]["serverInfo"]["name"], "artificial-continuity-lexi")
        self.assertEqual(response["result"]["capabilities"], {"tools": {"listChanged": False}})

    def test_tool_list_contains_only_two_reads(self):
        response = lexi_mcp.dispatch({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in response["result"]["tools"]}
        self.assertEqual(names, {"get_project_context", "search_project_context"})

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
