"""Reproduce the Mint host CLI contract, including versions with and without write guards."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import recall_desk as desk

# An executable, side-effect-isolated OpenClaw double. Its persisted JSON survives CLI invocations.
CLI = r'''import json,os,re,sys
from pathlib import Path
cfg=Path(os.environ["RECALL_TEST_CFG"])
keys=sys.argv[1:]
if keys==["config","set","--help"]:
    print("--strict-json --expect-current-absent --expect-current-json" if os.environ["RECALL_TEST_NEW"]=="1" else "--json")
    sys.exit(0)
verb=keys[1];path=keys[2];root=json.loads(cfg.read_text())
parts=[int(idx) if idx else name for name,idx in re.findall(r"([^.[\]]+)|\[(\d+)\]",path)]
def get(parts):
    x=root
    for k in parts: x=x[k]
    return x
sentinel=object()
try:old=get(parts)
except (KeyError,IndexError):old=sentinel
if verb=="get":
    if old is sentinel:
        print("Config path not found: "+path,file=sys.stderr);sys.exit(1)
    print(json.dumps(old));sys.exit(0)
if os.environ["RECALL_TEST_NEW"]=="1":
    if old is sentinel: assert "--expect-current-absent" in keys
    else:
        assert "--expect-current-json" in keys
        assert old==json.loads(keys[keys.index("--expect-current-json")+1])
else:
    assert "--expect-current-absent" not in keys and "--expect-current-json" not in keys
    if 'alsoAllow' in path: assert isinstance(parts[-1],int)
value=json.loads(keys[3]);parent=get(parts[:-1]);leaf=parts[-1]
if isinstance(leaf,int) and leaf==len(parent):parent.append(value)
else:parent[leaf]=value
cfg.write_text(json.dumps(root))
'''

class OpenClawHostIntegration(unittest.TestCase):
    def setup_variant(self,modern,profile="entries"):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            p=root/"openclaw";p.write_text("#!"+sys.executable+"\n"+CLI);p.chmod(0o700)
            live=root/"cli.json"
            policy={"tools":{"sandbox":{"tools":{"alsoAllow":["lexihydra__research","lexipost__text_post"]}}}}
            roster=({"entries":{"lexidaimon":policy}} if profile=="entries" else
                    {"list":[{"id":"main"},{"id":"lexidaimon",**policy}]})
            original={"mcp":{"servers":{"lexihydra":{"command":"/usr/bin/python3"}}},"agents":roster}
            live.write_text(json.dumps(original))
            private=root/"data";app=private/"recall_desk.py"
            with patch.multiple(desk,ROOT=private,DB=private/"clips.sqlite3",APP=app),patch.dict(os.environ,
                    RECALL_TEST_CFG=str(live),RECALL_TEST_NEW=str(int(modern)),PATH=str(root)+os.pathsep+os.environ.get("PATH","")):
                self.assertEqual(desk.install()["tool"],"totalrecall__find_capture")
                self.assertEqual(desk.install()["tool"],"totalrecall__find_capture")
                data=json.loads(live.read_text())
                expected=data["mcp"]["servers"]["totalrecall"]
                self.assertEqual(data["mcp"]["servers"]["lexihydra"],original["mcp"]["servers"]["lexihydra"])
                agent=(data["agents"]["entries"]["lexidaimon"] if profile=="entries" else data["agents"]["list"][1])
                self.assertEqual(agent["tools"]["sandbox"]["tools"]["alsoAllow"],["lexihydra__research","lexipost__text_post","totalrecall__find_capture"])
                message={"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                proc=subprocess.run([expected["command"],*expected["args"]],input=json.dumps(message)+"\n",capture_output=True,text=True,check=True)
                self.assertEqual(json.loads(proc.stdout)["result"]["tools"][0]["name"],desk.TOOL)
                old=b"# previous known managed source"
                app.write_bytes(old)
                (private/"installed-source.sha256").write_text(hashlib.sha256(old).hexdigest())
                desk.install()
                self.assertEqual(app.read_bytes(),Path(desk.__file__).read_bytes())
                app.write_text("# user modification")
                with self.assertRaises(desk.Issue): desk.install()
                self.assertEqual(app.read_text(),"# user modification")
    def test_hydra_guarded_contract(self): self.setup_variant(True)
    def test_legacy_settings_no_conditional_flags(self): self.setup_variant(False)
    def test_legacy_array_roster(self): self.setup_variant(False,"list")

if __name__=="__main__": unittest.main()
