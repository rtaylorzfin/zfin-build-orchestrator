"""Read `acli jira workitem view <KEY> --fields '*all' --json` on stdin; print a plain-text brief.

Jira returns descriptions and comments as ADF (Atlassian Document Format) trees; a crewmate
needs readable text, plus the links and attachment names it cannot fetch itself.
"""
import json
import sys


def adf(node):
    if node is None:
        return ""
    if isinstance(node, str):
        return node
    t = node.get("type")
    if t == "text":
        return node.get("text", "")
    if t == "hardBreak":
        return "\n"
    if t in ("mention", "emoji"):
        return node.get("attrs", {}).get("text", "")
    if t == "inlineCard":
        return node.get("attrs", {}).get("url", "")
    inner = "".join(adf(c) for c in node.get("content", []) or [])
    if t == "listItem":
        return "- " + inner.strip() + "\n"
    if t == "codeBlock":
        return "```\n" + inner + "\n```\n"
    if t == "heading":
        return "#" * node.get("attrs", {}).get("level", 2) + " " + inner + "\n"
    if t in ("paragraph", "blockquote", "tableRow", "rule"):
        return inner + "\n"
    if t == "tableCell" or t == "tableHeader":
        return inner.strip() + " | "
    return inner


d = json.load(sys.stdin)
f = d["fields"]
name = lambda u: (u or {}).get("displayName", "-")
print(f"# {d['key']}: {f.get('summary')}\n")
print(f"- Type: {f['issuetype']['name']} | Status: {f['status']['name']} | Assignee: {name(f.get('assignee'))}")
par = f.get("parent") or {}
if par:
    print(f"- Parent: {par.get('key')} {par.get('fields', {}).get('summary', '')}")
links = [(l["type"]["name"], (l.get("outwardIssue") or l.get("inwardIssue") or {}).get("key"))
         for l in f.get("issuelinks", [])]
if links:
    print("- Links: " + ", ".join(f"{k} ({t})" for t, k in links))
att = [a["filename"] for a in f.get("attachment", [])]
if att:
    print("- Attachments (not downloaded): " + ", ".join(att))
print("\n## Description\n")
print(adf(f.get("description")).strip() or "(none)")
comments = (f.get("comment") or {}).get("comments", [])
if comments:
    print("\n## Comments (oldest first)\n")
    for c in comments:
        print(f"### {name(c.get('author'))}, {c['created'][:10]}\n")
        print(adf(c["body"]).strip() + "\n")
