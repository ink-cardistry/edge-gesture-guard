#!/usr/bin/env python3
"""通过 api.github.com 的 Git Data API 完成 push（本机 github.com:443 被网络策略拦）。

用法：
  export GH_TOKEN=<有 repo 权限的 PAT>
  export GH_REPO=<owner>/<repo>
  python3 tools/push-via-api.py

设计要点：
- 只上传内容变化的 blob（blob sha 是内容寻址，远端相同就跳过）；
- 复刻本地 HEAD 的 tree/commit 元数据（作者/时间/消息），使远端提交与本地一致；
- 本地 main 需要先指向远端最新（脚本会以远端 main 为 parent），所以流程是
  "本地 commit -> 以远端 main 为父新建对象 -> 推进远端 main"。
- 无变化时只补标签，不产生空提交。
"""
import base64, json, os, subprocess, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = os.environ.get("GH_REPO", "")
API = "https://api.github.com"


def git(*args, binary=False):
    out = subprocess.check_output(["git"] + list(args), text=not binary)
    return out if binary else out.strip()


def api(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=180) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else {}


def to_iso(epoch, offset):
    sign = 1 if offset[0] == "+" else -1
    tz = timezone(sign * timedelta(hours=int(offset[1:3]), minutes=int(offset[3:5])))
    return datetime.fromtimestamp(int(epoch), tz).isoformat()


def ident(line):
    rest = line.split(" ", 1)[1]
    head, epoch, offset = rest.rsplit(" ", 2)
    name, email = head.rsplit(" <", 1)
    return {"name": name, "email": email.rstrip(">"), "date": to_iso(epoch, offset)}


def main():
    if not TOKEN or not REPO:
        raise SystemExit("need GH_TOKEN and GH_REPO")

    remote_main = api("GET", "/repos/%s/git/ref/heads/main" % REPO)["object"]["sha"]
    remote_tree = api("GET", "/repos/%s/git/commits/%s" % (REPO, remote_main))["tree"]["sha"]
    print("remote main =", remote_main[:12])

    local = {}
    for line in git("ls-tree", "-r", "HEAD").splitlines():
        meta, path = line.split("\t", 1)
        mode, typ, sha = meta.split()
        if typ == "blob":
            local[path] = (mode, sha)

    remote = {}
    for entry in api("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, remote_tree))["tree"]:
        if entry["type"] == "blob":
            remote[entry["path"]] = (entry["mode"], entry["sha"])

    to_upload = sorted(p for p, v in local.items() if remote.get(p) != v)
    to_delete = sorted(p for p in remote if p not in local)
    print("upload=%d delete=%d" % (len(to_upload), len(to_delete)))

    entries = []
    for path in to_upload:
        mode, sha = local[path]
        content = git("cat-file", "blob", sha, binary=True)
        res = api("POST", "/repos/%s/git/blobs" % REPO, {
            "content": base64.b64encode(content).decode(), "encoding": "base64"}) 
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": res["sha"]})
        print("  + %s" % path)
    for path in to_delete:
        entries.append({"path": path, "mode": remote[path][0], "type": "blob", "sha": None})

    if not entries and remote_tree == git("rev-parse", "HEAD^{tree}"):
        print("no source change")
        new_commit = None
    else:
        new_tree = api("POST", "/repos/%s/git/trees" % REPO,
                       {"base_tree": remote_tree, "tree": entries})["sha"] if entries else remote_tree
        raw = git("cat-file", "commit", "HEAD")
        header, _, message = raw.partition("\n\n")
        author_line = committer_line = None
        for line in header.splitlines():
            if line.startswith("author "):
                author_line = line
            elif line.startswith("committer "):
                committer_line = line
        new_commit = api("POST", "/repos/%s/git/commits" % REPO, {
            "message": message, "tree": new_tree, "parents": [remote_main],
            "author": ident(author_line), "committer": ident(committer_line),
        })["sha"]
        api("PATCH", "/repos/%s/git/refs/heads/main" % REPO,
            {"sha": new_commit, "force": False})
        print("main ->", new_commit[:12], "(tree", new_tree[:12] + ")")

    existing = set()
    try:
        for t in api("GET", "/repos/%s/git/refs/tags" % REPO):
            existing.add(t["ref"].replace("refs/tags/", ""))
    except urllib.error.HTTPError:
        pass
    for tag in git("tag", "-l").splitlines():
        if tag in existing:
            continue
        target = git("rev-parse", tag + "^{commit}")
        lines = git("tag", "-l", "-n99", tag).split("\n", 1)
        msg = lines[1].strip() if len(lines) > 1 and lines[1].strip() else tag
        if new_commit and target == git("rev-parse", "HEAD"):
            target = new_commit
        try:
            obj = api("POST", "/repos/%s/git/tags" % REPO,
                      {"tag": tag, "message": msg, "object": target, "type": "commit"})
            api("POST", "/repos/%s/git/refs" % REPO,
                {"ref": "refs/tags/" + tag, "sha": obj["sha"]})
            print("tag", tag, "->", target[:12])
        except urllib.error.HTTPError as exc:
            print("tag", tag, "failed:", exc.code)
    print("DONE")


if __name__ == "__main__":
    main()
