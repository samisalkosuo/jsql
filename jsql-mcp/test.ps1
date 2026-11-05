# End-to-end smoke test for server_info. Usage: ./test.ps1 [-Url http://localhost:9080/mcp] [-User alice -Password alicepw]
# Without -User the request is sent without an Authorization header (server default account is used, if configured).
param(
    [string]$Url = 'http://localhost:9080/mcp',
    [string]$User,
    [string]$Password = ''
)
$h = @('-H', 'Content-Type: application/json', '-H', 'Accept: application/json, text/event-stream')
if ($User) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:${Password}"))
    $h += @('-H', "Authorization: Basic $b64")
}
$dir = Join-Path $PSScriptRoot 'req'

"--- tools/list"
curl.exe -s @h --data-binary "@$dir/list.json" $Url
"`n--- server_info"
curl.exe -s @h --data-binary "@$dir/server_info.json" $Url
"`n--- unknown tool"
curl.exe -s @h --data-binary "@$dir/unknown.json" $Url
