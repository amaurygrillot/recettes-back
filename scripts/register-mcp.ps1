<#
.SYNOPSIS
    Logs in to recettes-back and (re)registers its MCP server in Claude Code with the fresh bearer token.

.DESCRIPTION
    POSTs the credentials to /auth/login, then runs `claude mcp remove` followed by `claude mcp add` so the same
    command both adds a new entry and updates an existing one. Run it again after every app restart: the signing
    key is regenerated at startup, so every previously issued token stops working (see docs/mcp-server.md).

    This is the Windows counterpart of register-mcp.sh, which does the same from WSL.

.PARAMETER Username
    Account to log in with.

.PARAMETER Password
    Account password. Omit it to be prompted without echo, which keeps it out of the shell history.

.PARAMETER BaseUrl
    Where the API listens. Defaults to http://localhost:8080.

.PARAMETER Name
    Name of the MCP server entry in Claude Code. Defaults to "recettes".

.PARAMETER Scope
    Claude Code config scope: local, project or user. Defaults to local.

.EXAMPLE
    .\scripts\register-mcp.ps1 -Username amaury
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Username,
    [string] $Password,
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $Name = 'recettes',
    [ValidateSet('local', 'project', 'user')] [string] $Scope = 'local'
)

$ErrorActionPreference = 'Stop'

# The Claude desktop app bundles the CLI in a versioned folder instead of putting it on PATH, so fall back to
# the newest bundled copy. The Microsoft Store build redirects the app's %APPDATA% into its package folder, so
# from an ordinary terminal the copy is under LocalCache, not under the real %APPDATA%; look in both.
$claude = (Get-Command claude -ErrorAction SilentlyContinue).Source
if (-not $claude) {
    $bundleRoots = @("$env:APPDATA\Claude\claude-code") +
        @(Get-ChildItem "$env:LOCALAPPDATA\Packages" -Filter 'Claude_*' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { "$($_.FullName)\LocalCache\Roaming\Claude\claude-code" })
    $claude = $bundleRoots | Where-Object { Test-Path $_ } |
        ForEach-Object { Get-ChildItem $_ -Recurse -Filter claude.exe -ErrorAction SilentlyContinue } |
        Sort-Object { [version]($_.FullName -replace '.*\\claude-code\\([\d.]+)\\.*', '$1') } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $claude) {
    throw "The 'claude' CLI is neither on PATH nor bundled with the Claude desktop app."
}
Write-Host "Using $claude"

if (-not $Password) {
    $secure = Read-Host -Prompt "Password for $Username" -AsSecureString
    $Password = [System.Net.NetworkCredential]::new('', $secure).Password
}

$baseUrl = $BaseUrl.TrimEnd('/')

Write-Host "Logging in to $baseUrl/auth/login as $Username..."
try {
    $response = Invoke-WebRequest -Uri "$baseUrl/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -UseBasicParsing
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    throw "Login failed$(if ($status) { " (HTTP $status)" }): $($_.Exception.Message)"
}

$token = $response.Content.Trim().Trim('"')
if (-not $token) {
    throw 'Login succeeded but the response held no token.'
}

# Windows PowerShell 5.1 turns any stderr line from a native command into a terminating error while
# $ErrorActionPreference is 'Stop', and `mcp remove` writes one when the entry does not exist yet, which is the
# normal first-run case. So judge these calls by exit code alone.
$ErrorActionPreference = 'Continue'

& $claude mcp remove $Name --scope $Scope 2>&1 | Out-Null

& $claude mcp add --transport http --scope $Scope $Name "$baseUrl/mcp" --header "Authorization: Bearer $token"
if ($LASTEXITCODE -ne 0) {
    throw "'claude mcp add' failed with exit code $LASTEXITCODE."
}

Write-Host "MCP server '$Name' registered ($Scope scope). Restart the Claude Code session to pick it up."
