#!/usr/bin/env python3
"""Command line test tool for the jsql-mcp server (Streamable HTTP, optional HTTP Basic auth).

Subcommands:
  list    list the tools of the server
  call    call one tool
  smoke   run automated checks against the server
  raw     send a raw JSON-RPC body over HTTP (protocol level tests)

Common options can also be set with MCP_URL, MCP_USER and MCP_PASSWORD.
Without --user no Authorization header is sent (the server default account is used, if configured).
"""
import argparse
import asyncio
import base64
import contextlib
import json
import os
import sys
from urllib.parse import urlsplit, urlunsplit

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

DEFAULT_URL = "http://localhost:9080/mcp"

EXPECTED_TOOLS = {
    "server_info", "list_databases", "list_schemas", "list_tables", "describe_table",
    "search_schema", "get_relationships", "sample_rows", "execute_query", "profile_column",
    "validate_sql", "explain_query",
}


def auth_headers(args):
    if not args.user:
        return {}
    token = base64.b64encode(f"{args.user}:{args.password or ''}".encode("utf-8")).decode("ascii")
    return {"Authorization": f"Basic {token}"}


def http_client(args):
    return httpx.AsyncClient(
        headers=auth_headers(args), timeout=httpx.Timeout(args.timeout), follow_redirects=True)


@contextlib.asynccontextmanager
async def open_session(args):
    """Open an initialized MCP client session."""
    async with http_client(args) as client:
        async with streamable_http_client(args.url, http_client=client) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                yield session


def result_text(result):
    parts = []
    for block in result.content:
        parts.append(block.text if getattr(block, "type", None) == "text" else block.model_dump_json())
    return "\n".join(parts)


def coerce(value):
    """Turn a key=value string value into bool, int, float or str."""
    lowered = value.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    for cast in (int, float):
        try:
            return cast(value)
        except ValueError:
            pass
    return value


def tool_arguments(args):
    arguments = json.loads(args.args) if args.args else {}
    if not isinstance(arguments, dict):
        raise ValueError("--args must be a JSON object")
    for pair in args.arg or []:
        key, sep, value = pair.partition("=")
        if not sep or not key:
            raise ValueError(f"-a expects key=value, got: {pair}")
        arguments[key] = coerce(value)
    return arguments


def health_url(args, name):
    parts = urlsplit(args.url)
    return urlunsplit((parts.scheme, parts.netloc, f"/health/{name}", "", ""))


# ---------------------------------------------------------------- list

async def cmd_list(args):
    async with open_session(args) as session:
        tools = (await session.list_tools()).tools
    if args.json:
        print(json.dumps([t.model_dump(mode="json", exclude_none=True) for t in tools], indent=2))
        return 0
    for tool in sorted(tools, key=lambda t: t.name):
        props = (tool.inputSchema or {}).get("properties", {})
        required = set((tool.inputSchema or {}).get("required", []))
        params = ", ".join(f"{p}{'*' if p in required else ''}" for p in props)
        read_only = tool.annotations.readOnlyHint if tool.annotations else None
        flag = " [read-only]" if read_only else ""
        print(f"{tool.name}({params}){flag}")
        if tool.description:
            print(f"    {tool.description.strip().splitlines()[0]}")
    print(f"\n{len(tools)} tools (* = required parameter)")
    return 0


# ---------------------------------------------------------------- call

async def cmd_call(args):
    arguments = tool_arguments(args)
    async with open_session(args) as session:
        result = await session.call_tool(args.tool, arguments)
    print(result_text(result))
    if result.isError:
        print("(tool reported an error)", file=sys.stderr)
        return 1
    return 0


# ---------------------------------------------------------------- raw

async def cmd_raw(args):
    body = args.body
    if body.startswith("@"):
        with open(body[1:], "r", encoding="utf-8") as f:
            body = f.read()
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    headers.update(auth_headers(args))
    for h in args.header or []:
        name, sep, value = h.partition(":")
        if not sep:
            raise ValueError(f"-H expects 'Name: value', got: {h}")
        headers[name.strip()] = value.strip()
    if args.no_auth:
        headers.pop("Authorization", None)
    async with httpx.AsyncClient(timeout=httpx.Timeout(args.timeout)) as client:
        response = await client.post(args.url, content=body.encode("utf-8"), headers=headers)
    print(f"HTTP {response.status_code}")
    for name, value in response.headers.items():
        print(f"{name}: {value}")
    print()
    print(response.text)
    return 0 if response.is_success else 1


# ---------------------------------------------------------------- smoke

class Report:
    def __init__(self):
        self.failed = 0

    def check(self, name, ok, detail=""):
        if not ok:
            self.failed += 1
        suffix = f" - {detail}" if detail else ""
        print(f"{'PASS' if ok else 'FAIL'}  {name}{suffix}")


async def smoke_http(args, report):
    async with httpx.AsyncClient(timeout=httpx.Timeout(args.timeout)) as client:
        for name in ("live", "ready"):
            url = health_url(args, name)
            try:
                r = await client.get(url)
                report.check(f"health/{name}", r.status_code == 200, f"HTTP {r.status_code}")
            except httpx.HTTPError as e:
                report.check(f"health/{name}", False, str(e))
        # Other schemes than Basic must be rejected, not ignored.
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "Authorization": "Bearer abc"}
        body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"server_info","arguments":{}}}'
        try:
            r = await client.post(args.url, content=body.encode("utf-8"), headers=headers)
            text = r.text
            rejected = r.status_code >= 400 or '"isError":true' in text or '"error"' in text
            report.check("Bearer authorization rejected", rejected, f"HTTP {r.status_code}")
        except httpx.HTTPError as e:
            report.check("Bearer authorization rejected", False, str(e))


async def smoke_mcp(args, report):
    try:
        async with open_session(args) as session:
            report.check("initialize", True)
            tools = (await session.list_tools()).tools
            names = {t.name for t in tools}
            missing = sorted(EXPECTED_TOOLS - names)
            extra = sorted(names - EXPECTED_TOOLS)
            detail = f"{len(names)} tools"
            if missing:
                detail += f", missing: {', '.join(missing)}"
            if extra:
                detail += f", unexpected: {', '.join(extra)}"
            report.check("tools/list has the expected tools", not missing, detail)

            result = await session.call_tool("server_info", {})
            text = result_text(result)
            report.check("server_info", not result.isError, text[:120].replace("\n", " "))

            for tool in ("list_databases", "list_schemas", "list_tables"):
                if tool not in names:
                    continue
                result = await session.call_tool(tool, {})
                report.check(tool, not result.isError, "" if not result.isError else result_text(result)[:120])

            try:
                result = await session.call_tool("no_such_tool", {})
                report.check("unknown tool returns an error", bool(result.isError))
            except Exception as e:  # the SDK may raise for JSON-RPC errors
                report.check("unknown tool returns an error", True, type(e).__name__)
    except Exception as e:
        report.check("MCP session", False, f"{type(e).__name__}: {e}")


async def cmd_smoke(args):
    report = Report()
    await smoke_http(args, report)
    await smoke_mcp(args, report)
    print(f"\n{'ALL CHECKS PASSED' if not report.failed else str(report.failed) + ' CHECK(S) FAILED'}")
    return 1 if report.failed else 0


# ---------------------------------------------------------------- main

def build_parser():
    parser = argparse.ArgumentParser(prog="mcptest", description="Test tool for the jsql-mcp server.")
    parser.add_argument("--url", default=os.environ.get("MCP_URL", DEFAULT_URL),
                        help=f"MCP endpoint (env MCP_URL, default {DEFAULT_URL})")
    parser.add_argument("--user", default=os.environ.get("MCP_USER"), help="user name (env MCP_USER)")
    parser.add_argument("--password", default=os.environ.get("MCP_PASSWORD", ""),
                        help="password (env MCP_PASSWORD)")
    parser.add_argument("--timeout", type=float, default=30, help="timeout in seconds (default 30)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("list", help="list tools")
    p.add_argument("--json", action="store_true", help="print the full tool definitions as JSON")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("call", help="call one tool")
    p.add_argument("tool", help="tool name, e.g. list_tables")
    p.add_argument("--args", help='arguments as JSON object, e.g. \'{"schema":"sales"}\'')
    p.add_argument("-a", "--arg", action="append", metavar="KEY=VALUE",
                   help="tool argument (repeatable); true/false and numbers are converted")
    p.set_defaults(func=cmd_call)

    p = sub.add_parser("smoke", help="run automated checks")
    p.set_defaults(func=cmd_smoke)

    p = sub.add_parser("raw", help="POST a raw JSON-RPC body and print the HTTP response")
    p.add_argument("body", help="JSON body, or @file (e.g. @req/init.json)")
    p.add_argument("-H", "--header", action="append", metavar="'Name: value'",
                   help="extra or overriding request header (repeatable)")
    p.add_argument("--no-auth", action="store_true", help="do not send an Authorization header")
    p.set_defaults(func=cmd_raw)
    return parser


def unwrap(exc):
    """Find the root cause inside (nested) exception groups."""
    while isinstance(exc, BaseExceptionGroup) and exc.exceptions:
        exc = exc.exceptions[0]
    return exc


def main():
    args = build_parser().parse_args()
    try:
        return asyncio.run(args.func(args))
    except (ValueError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    except BaseException as e:  # noqa: BLE001 - report any transport/SDK failure briefly
        cause = unwrap(e)
        if isinstance(cause, KeyboardInterrupt):
            return 130
        if isinstance(cause, httpx.HTTPStatusError):
            print(f"error: HTTP {cause.response.status_code} from {args.url}", file=sys.stderr)
        else:
            print(f"error: {type(cause).__name__}: {cause}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
