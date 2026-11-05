# Calls one MCP tool with curl and prints the text of the result.
# Usage: ./call.ps1 list_tables '{"schema":"sales"}' [-Url http://localhost:9080/mcp] [-User postgres -Password pgpw]
# Without -User no Authorization header is sent (the server's default account is used, if configured).
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Tool,
    [Parameter(Position = 1)][string]$Arguments = '{}',
    [string]$Url = 'http://localhost:9080/mcp',
    [string]$User,
    [string]$Password = ''
)
$h = @('-H', 'Content-Type: application/json', '-H', 'Accept: application/json, text/event-stream')
if ($User) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:${Password}"))
    $h += @('-H', "Authorization: Basic $b64")
}
if ($Tool -eq 'tools/list') {
    $body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
} else {
    $body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"' + $Tool + '","arguments":' + $Arguments + '}}'
}
$tmp = New-TemporaryFile
try {
    [IO.File]::WriteAllText($tmp, $body, (New-Object Text.UTF8Encoding $false))
    curl.exe -s --max-time 30 @h --data-binary "@$tmp" $Url
    ""
} finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
}
