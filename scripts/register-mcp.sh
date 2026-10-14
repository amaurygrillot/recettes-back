#!/usr/bin/env bash
# Logs in to recettes-back and (re)registers its MCP server in Claude Code with the fresh bearer token.
#
# POSTs the credentials to /auth/login, then runs `claude mcp remove` followed by `claude mcp add`, so the same
# command both adds a new entry and updates an existing one. Run it again after every app restart: the signing key is
# regenerated at startup, so every previously issued token stops working (see docs/mcp-server.md).
#
# Usage: scripts/register-mcp.sh -u USERNAME [-p PASSWORD] [-b BASE_URL] [-n NAME] [-s SCOPE]
#   -u  account to log in with (required)
#   -p  its password; omit to be prompted without echo, which keeps it out of the shell history
#   -b  where the API listens (default: http://localhost:8080, or under WSL2 NAT, where localhost does not reach
#       Windows, the Windows host's address)
#   -n  name of the MCP server entry in Claude Code (default recettes)
#   -s  Claude Code config scope: local, project or user (default local)

set -euo pipefail

base_url=""
name="recettes"
scope="local"
username=""
password=""

usage() {
    sed -n '/^# Usage/,/^# *-s/p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

while getopts "u:p:b:n:s:h" opt; do
    case "$opt" in
        u) username="$OPTARG" ;;
        p) password="$OPTARG" ;;
        b) base_url="$OPTARG" ;;
        n) name="$OPTARG" ;;
        s) scope="$OPTARG" ;;
        *) usage ;;
    esac
done

[[ -n "$username" ]] || usage
case "$scope" in local | project | user) ;; *) echo "Scope must be local, project or user." >&2; exit 2 ;; esac
command -v claude >/dev/null || { echo "The 'claude' CLI is not on PATH." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required." >&2; exit 1; }

if [[ -z "$password" ]]; then
    read -r -s -p "Password for $username: " password
    echo
fi

# Any HTTP answer counts as reachable; only a failure to connect does not.
reachable() { curl -s -o /dev/null --max-time 2 "$1/health"; }

if [[ -z "$base_url" ]]; then
    base_url="http://localhost:8080"
    if ! reachable "$base_url"; then
        # WSL2 in NAT mode: localhost is the Linux VM, Windows is the default gateway.
        gateway="$(ip route 2>/dev/null | awk '/^default/ {print $3; exit}')"
        if [[ -n "$gateway" ]] && reachable "http://$gateway:8080"; then
            base_url="http://$gateway:8080"
            echo "localhost:8080 is unreachable; using the Windows host at $base_url"
        fi
    fi
fi
base_url="${base_url%/}"

# Minimal JSON string escaping: backslash first, then the double quote.
json_escape() { printf '%s' "$1" | sed -e 's/[\]/&&/g' -e 's/"/\\"/g'; }

echo "Logging in to $base_url/auth/login as $username..."
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
status="$(curl -sS -o "$body_file" -w '%{http_code}' -X POST "$base_url/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$(json_escape "$username")\",\"password\":\"$(json_escape "$password")\"}")"

if [[ "$status" != 200 ]]; then
    echo "Login failed (HTTP $status)." >&2
    exit 1
fi

token="$(tr -d '"[:space:]' < "$body_file")"
[[ -n "$token" ]] || { echo "Login succeeded but the response held no token." >&2; exit 1; }

# `mcp remove` fails when the entry does not exist yet, which is the normal first-run case.
claude mcp remove "$name" --scope "$scope" >/dev/null 2>&1 || true

claude mcp add --transport http --scope "$scope" "$name" "$base_url/mcp" \
    --header "Authorization: Bearer $token"

echo "MCP server '$name' registered ($scope scope). Restart the Claude Code session to pick it up."
