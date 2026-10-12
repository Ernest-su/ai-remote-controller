"""Deterministic model decisions that validate real file-tool feedback."""
import json

PROMPT = "create inspect and prepare deletion of fixture directory"
PATH = "model-created-directory"
TOOLS = ["files.mkdir", "files.stat", "files.delete"]


def reply(messages, schemas):
    user_index = max(i for i, item in enumerate(messages) if item["role"] == "user")
    if messages[user_index]["content"] != PROMPT:
        return None
    names = {item["function"]["name"] for item in schemas}
    assert all(name.replace(".", "_") in names for name in TOOLS)
    assert not {"approve", "approve_file", "approve_app", "approve_install", "approve_cache", "approve_system"} & names
    results = [item for item in messages[user_index + 1:] if item["role"] == "tool"]
    for index, item in enumerate(results):
        assert item["tool_call_id"] == f"file-call-{index}"
        result = json.loads(item["content"])
        assert result["ok"], result
        output = json.loads(result["output"])
        assert output["root"] == "inbox" and output["path"] == PATH, output
        if index == 0:
            assert output["created"] is True, output
        elif index == 1:
            assert output["kind"] == "directory", output
        elif index == 2:
            assert output["kind"] == "delete" and output["directory"] is True, output
            assert output["approvalRequired"] is True and output["id"], output
    if len(results) == len(TOOLS):
        return {"role": "assistant", "content": "directory created; deletion awaits user approval"}
    assert len(results) < len(TOOLS), results
    index = len(results)
    arguments = {"root": "inbox", "path": PATH}
    if TOOLS[index] == "files.delete":
        arguments["recursive"] = True
    return {"role": "assistant", "content": None, "tool_calls": [{
        "id": f"file-call-{index}", "type": "function", "function": {
            "name": TOOLS[index].replace(".", "_"), "arguments": json.dumps(arguments),
        },
    }]}
