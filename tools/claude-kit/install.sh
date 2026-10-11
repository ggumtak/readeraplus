#!/bin/sh
# Copies the Opus/Sonnet/Haiku agent setup into another repository:
#   tools/claude-kit/install.sh /path/to/other-repo
# Existing agent files with the same name and an existing .claude/settings.json are kept (never overwritten);
# the rules block is appended to CLAUDE.md once.
set -eu
kit=$(cd "$(dirname "$0")" && pwd)
dest=${1:?usage: install.sh /path/to/repo}
[ -d "$dest" ] || { echo "no such directory: $dest" >&2; exit 1; }
mkdir -p "$dest/.claude/agents"
for f in "$kit"/agents/*.md; do
    t="$dest/.claude/agents/$(basename "$f")"
    if [ -e "$t" ]; then echo "kept existing $(basename "$f")"; else cp "$f" "$t"; echo "added $(basename "$f")"; fi
done
if [ -e "$dest/.claude/settings.json" ]; then
    echo "kept existing .claude/settings.json (set \"model\": \"claude-opus-5-5\", \"effortLevel\": \"high\" yourself)"
else
    cp "$kit/settings.json" "$dest/.claude/settings.json"; echo "added .claude/settings.json"
fi
if grep -q "^## 멀티 에이전트 운영 규칙" "$dest/CLAUDE.md" 2>/dev/null; then
    echo "CLAUDE.md already has the rules block"
else
    { [ -s "$dest/CLAUDE.md" ] && printf '\n'; cat "$kit/CLAUDE-agents.md"; } >> "$dest/CLAUDE.md"
    echo "appended the rules block to CLAUDE.md"
fi
