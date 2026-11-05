# jsql

JDBC tools: a command line client to test JDBC connections and execute SQL statements, and an MCP server that lets AI tools investigate a database.

## Use case

* Test that network connection works from client to database.
* Execute simple SQL statements.

## Build

Build using Maven. The project is a multi-module build:

* `jsql-core`: shared JDBC connection and query logic.
* `jsql-cli`: the command line tool, built as `jsql-cli/target/jsql-1.0-SNAPSHOT-jar-with-dependencies.jar`.

```
mvn package
```

Usage: see sample commands below.


## Sample commands

### Connect to database

JDBC URL, user name and password is given and connection is done to database.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd
```

If connection is successful, basic information about the database is printed out.

```
Connection established successfully.
Product name    : DB2/LINUXX8664
Product version : SQL110580
Database version: 11.5
Driver name     : IBM Data Server Driver for JDBC and SQLJ
Driver version  : 4.32.28
JDBC URL        : jdbc:db2://127.0.0.1:50000/sampledb
JDBC version    : 4.1
User name       : db2inst1
Connection closed.
```

### List tables

Option -t lists all tables in the database

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd -t
```

Output of -t includes table names

```
Connection established successfully.
Product name    : DB2/LINUXX8664
Product version : SQL110580
Database version: 11.5
Driver name     : IBM Data Server Driver for JDBC and SQLJ
Driver version  : 4.32.28
JDBC URL        : jdbc:db2://127.0.0.1:50000/sampledb
JDBC version    : 4.1
User name       : db2inst1

Tables:
ADMINTABCOMPRESSINFO
ADMINTABINFO
ADMINTEMPCOLUMNS
ADMINTEMPTABLES
APPLICATIONS
APPL_PERFORMANCE
...
<all table names>
...
Connection closed.
```

Option -r is regex to filter table names.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd -t -r "^XS"
```

Filtered table names output.

```
Connection established successfully.
Product name    : DB2/LINUXX8664
Product version : SQL110580
Database version: 11.5
Driver name     : IBM Data Server Driver for JDBC and SQLJ
Driver version  : 4.32.28
JDBC URL        : jdbc:db2://127.0.0.1:50000/sampledb
JDBC version    : 4.1
User name       : db2inst1

Tables (^XS):
XSROBJECTAUTH
XSROBJECTCOMPONENTS
XSROBJECTDEP
XSROBJECTDETAILS
XSROBJECTHIERARCHIES
XSROBJECTS

Connection closed.
```

### Execute SQL

Create new table using -s option.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd   -s "create table mytable (cola int, colb varchar(8))"
```

Create table output.

```
Connection established successfully.
...
<database info>
...
SQL:
create table mytable (cola int, colb varchar(8))

Result: 0

Connection closed.
```

Insert row using -s option.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd   -s "insert into mytable values (0,'hello')"
```

Output of insert row command.

```
Connection established successfully.
...
<database info>
...
SQL:
insert into mytable values (0,'hello')

Result: 1

Connection closed.
```

Execute select statement.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j jdbc:db2://127.0.0.1:50000/sampledb -u db2inst1 -p passw0rd   -s "select * from mytable"
```

Select statement output.

```
Connection established successfully.
...
<database info>
...
SQL:
select * from mytable

COLA,COLB
0,hello
0,world

Connection closed.
```

### Accept self-signed certificates

Option -k (--insecure) accepts all TLS/SSL certificates, including self-signed ones. Use it only for testing, since server certificate validation is disabled.

```
java -jar .\jsql-cli\target\jsql-1.0-SNAPSHOT-jar-with-dependencies.jar -j "jdbc:presto://127.0.0.1:8443/catalog/schema?SSL=true" -u user -p passw0rd -k
```

Option -k adds the following connection properties, depending on the JDBC URL:

| Database | Properties |
|---|---|
| Presto | SSL=true, SSLVerification=NONE |
| SQL Server | encrypt=true, trustServerCertificate=true |
| PostgreSQL | ssl=true, sslfactory=org.postgresql.ssl.NonValidatingFactory |
| MySQL | sslMode=REQUIRED |
| MariaDB | sslMode=trust |

For other databases (Db2, Oracle, Informix), -k has no effect and a warning is printed.

## MCP server

The MCP server lets an AI tool (for example Bob) investigate a database that you have a JDBC connection to but no documentation for: which schemas and tables exist, what the columns hold, how tables relate, what the data looks like, and then run queries.

* Open Liberty, Streamable HTTP, stateless. The endpoint is `/mcp`. There is no stdio mode.
* The server speaks plain HTTP. If you want HTTPS, put a route, ingress or proxy in front of it. The `http://` or `https://` in the client's MCP configuration is all that decides.
* One container serves one database (one JDBC URL). Run one container per database.
* All configuration is done with environment variables.

### Tools

| Tool | What it does |
|---|---|
| `server_info` | Which database and driver the server is connected to, which account is used, read-only mode and limits. Also checks the credentials, so call it first. |
| `list_databases` | Catalogs (databases) visible on the connection. Often empty or a single entry. |
| `list_schemas` | Schemas. The usual starting point. |
| `list_tables` | Tables and views, filtered by schema, name pattern (`%`, `_`) and type. Indexes, sequences and system objects are left out unless asked for with `types`. |
| `describe_table` | Columns (type, size, nullable, default, comment), primary key and indexes. |
| `search_schema` | Tables and columns whose name matches a case-insensitive regular expression. |
| `get_relationships` | Foreign keys of a table: `references` (points to) and `referencedBy` (pointed to by). |
| `sample_rows` | The first rows of a table or view (not a random sample). |
| `execute_query` | Runs one SQL statement. Rows are limited and marked `truncated`. |
| `profile_column` | Row, null and distinct counts, min, max and most frequent values of one column. Scans the table several times, so use it with care on large tables. |
| `validate_sql` | Asks the database to parse a statement without running it. Returns the result columns and parameter count, or the database's error. |
| `explain_query` | Execution plan of a statement, without running it. PostgreSQL, MySQL, MariaDB, H2, Presto/Trino and Oracle. Db2, SQL Server and Informix have no plain-SQL explain and return an error that says so. |

Results are compact JSON text. Rows are arrays in column order, SQL NULL is JSON `null`. Long text is cut and ends in `...[truncated]`, binary values are shown as `<BLOB 12 bytes>` or `<binary 12 bytes>`. A list that was cut at the row limit has `"truncated": true`.

Table and schema names are matched as typed. If nothing is found, the upper case and lower case forms are tried, because databases fold case differently. An ambiguous table name (the same name in several schemas) returns an error that lists the candidates.

### Run with a container

The image is built from the repository root with `Dockerfile.mcp` (GitHub Actions publishes it as `docker.io/kazhar/jsql-mcp`, see [Container image](#container-image)).

```
podman build -f Dockerfile.mcp -t jsql-mcp .
podman run --rm -p 9080:9080 -e JSQL_JDBC_URL=jdbc:postgresql://dbhost:5432/mydb jsql-mcp
```

The MCP endpoint is then `http://localhost:9080/mcp`. The container must be able to reach the database: `localhost` inside the container is the container itself. Use a host name or address that the container can reach, or run both containers on the same podman/docker network and use the database container's name in the JDBC URL.

With a default account, so that clients do not have to send one:

```
podman run --rm -p 9080:9080 \
  -e JSQL_JDBC_URL=jdbc:postgresql://dbhost:5432/mydb \
  -e JSQL_USER_NAME=readonly_user -e JSQL_USER_PASSWORD=secret \
  -e JSQL_READ_ONLY=true \
  jsql-mcp
```

Health checks for container platforms: `/health/live` (the server runs) and `/health/ready` (the configuration is valid). If an environment variable is wrong, the server still starts, `/health/ready` returns 503, and every tool call returns the list of problems. That is easier to find on a platform than a crash loop.

### Configuration

| Environment variable | Default | Description |
|---|---|---|
| `JSQL_JDBC_URL` | (required) | JDBC URL of the database. Same name as in the CLI. |
| `JSQL_USER_NAME`, `JSQL_USER_PASSWORD` | (none) | Optional default account, used when a request has no `Authorization` header. Both or neither. Same names as in the CLI. |
| `JSQL_READ_ONLY` | `false` | `true` rejects statements that are not reads and sets the connection read-only. See [Security notes](#security-notes). |
| `JSQL_MAX_ROWS` | `1000` | Rows (or list entries) returned by one call, 1 to 100000. |
| `JSQL_QUERY_TIMEOUT` | `60` | Statement timeout in seconds, 0 for none. |
| `JSQL_MAX_VALUE_LENGTH` | `1000` | Characters returned per value. Longer text is cut. |
| `JSQL_INSECURE` | `false` | Accept any TLS certificate of the database. Same effect and same supported drivers as CLI option `-k`. Testing only. |
| `JSQL_POOL_SIZE` | `5` | Connections per connection pool. |
| `JSQL_POOL_IDLE_TTL` | `600` | Seconds without use after which a pool is closed. |
| `JSQL_MAX_POOLS` | `20` | Pools (distinct accounts) open at the same time. |
| `JSQL_CONNECT_TIMEOUT` | `30` | Seconds to wait for a database connection. |
| `JSQL_HTTP_PORT` | `9080` | HTTP port inside the container. |

### Accounts and connection pooling

The database account comes from the `Authorization: Basic <base64 of user:password>` header of each request. If a request has no such header, the default account (`JSQL_USER_NAME`, `JSQL_USER_PASSWORD`) is used. If there is neither, the tool call fails with a message that says what to do. Other schemes (for example `Bearer`) are rejected, not ignored, so a client never silently gets the default account.

There is no user management in the server: the database checks the credentials when the server connects. Wrong credentials give the database's own error message.

The server keeps one connection pool (HikariCP) per distinct account, so users who share a container do not disturb each other. A pool that has not been used for `JSQL_POOL_IDLE_TTL` seconds is closed. At most `JSQL_MAX_POOLS` pools are open; a pool in use is never closed, and when all of them are in use a new account gets a "Too many different database accounts" error.

### Bob configuration

Bob reads MCP servers from `~/.bob/settings/mcp.json` (global) or `.bob/mcp.json` (project; project wins when the name is the same). The `headers` field sets the `Authorization` header.

Create the header value (the user name and password separated by a colon, base64 encoded):

```
# PowerShell
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("myuser:mypassword"))

# bash
printf '%s' 'myuser:mypassword' | base64
```

Container on your own machine

```
{
  "mcpServers": {
    "jsql": {
      "type": "streamable-http",
      "url": "http://localhost:9080/mcp",
      "headers": { "Authorization": "Basic bXl1c2VyOm15cGFzc3dvcmQ=" },
      "alwaysAllow": ["server_info", "list_schemas", "list_tables", "describe_table", "search_schema", "get_relationships"]
    }
  }
}
```

Container on a cluster, behind a TLS route

```
{
  "mcpServers": {
    "jsql": {
      "type": "streamable-http",
      "url": "https://jsql-mcp.apps.example.com/mcp",
      "headers": { "Authorization": "Basic bXl1c2VyOm15cGFzc3dvcmQ=" }
    }
  }
}
```

If the server has a default account, leave `headers` out (or empty).

Notes:

* The header is only base64, not encrypted. For anything that is not your own machine, use `https://`.
* Put the header in the global `~/.bob/settings/mcp.json`, not in `.bob/mcp.json` of a project, because the project file is meant to be shared and committed.
* `alwaysAllow` lists tools that run without asking. The list above is reasonable for investigation: these tools only read metadata. Consider asking for approval on `sample_rows` and `profile_column` (they read table data), and always on `execute_query`, which can change data unless the server runs in read-only mode.
* Call `server_info` first. It shows the database, the account in use and the limits, and it fails with a readable message if the credentials or the server configuration are wrong.

### Deploy on OpenShift or Kubernetes

[openshift/jsql-mcp-deployment.yaml](openshift/jsql-mcp-deployment.yaml) has a Deployment, a Service and a Route with edge TLS. The database settings are read from a secret:

```
oc create secret generic jsql-mcp-db \
  --from-literal=JSQL_JDBC_URL='jdbc:postgresql://dbhost:5432/mydb' \
  --from-literal=JSQL_USER_NAME='readonly_user' \
  --from-literal=JSQL_USER_PASSWORD='...'
oc apply -f openshift/jsql-mcp-deployment.yaml
oc get route jsql-mcp
```

The secret's user name and password are optional; leave them out to make every user send their own in the header. Other settings (`JSQL_READ_ONLY`, `JSQL_MAX_ROWS`, ...) are plain `env` entries in the Deployment. The manifest has not been applied to a cluster yet; only the container image has been run (with podman).

### Container image

[.github/workflows/jsql-mcp-container.yaml](.github/workflows/jsql-mcp-container.yaml) builds `Dockerfile.mcp` and pushes `docker.io/kazhar/jsql-mcp` when something is pushed to `main` (tag `latest`) or `dev` (tag `dev`), in the same way as the CLI images.

### Security notes

* **The database's permissions are the real access control.** Use a database account that can only do what you want the AI tool to do. For investigation that is normally read access to the schemas in question.
* **`JSQL_READ_ONLY=true` is a safety net, not security.** It rejects a statement unless it starts with `SELECT`, `WITH`, `EXPLAIN`, `SHOW`, `DESCRIBE` or `VALUES`, rejects data-changing keywords such as `INSERT`, `UPDATE`, `DELETE`, `DROP`, `CALL` and `INTO` outside text literals and comments, allows only one statement per call, and sets the JDBC connection to read-only where the driver allows it. It is a text check. It cannot see what a database function or procedure does when it is called from a `SELECT`, and some drivers ignore the read-only flag. Do not rely on it to protect data that the account can write.
* Without `JSQL_READ_ONLY`, the server does not look at the SQL. A model that is told to run an `UPDATE` will run it.
* The server has no login of its own. Anyone who can reach the endpoint can try database logins (the database's own lockout rules apply). Keep the endpoint inside the network, or protect it with the route, network policy or proxy that suits the database.
* Passwords in the `Authorization` header are not logged. The JDBC URL shown by `server_info` and in the log has passwords removed (`password=***`).
* Row, time and value limits (`JSQL_MAX_ROWS`, `JSQL_QUERY_TIMEOUT`, `JSQL_MAX_VALUE_LENGTH`) protect the server and the model's context window, not the data.

### What has been tested

The tools have been run end to end through the container image against **PostgreSQL 16** and **Db2 12**. The core logic is also tested with H2. The other bundled drivers (Oracle, MySQL, MariaDB, SQL Server, Informix, Presto) work with the CLI and are expected to work through `DatabaseMetaData`, but the MCP tools have not been tried on them. Things most likely to differ are metadata case folding, large-object handling and `explain_query`. MySQL in particular reads a whole result into memory unless told otherwise, so `sample_rows` on a very large table may be slow there.

### Troubleshooting

* **A tool says the server is not configured correctly.** An environment variable is missing or invalid; the message lists them. `/health/ready` shows the same.
* **The application log.** The container console only shows important messages. Details such as "Opened connection pool ... for user ..." and unexpected errors are in `/logs/messages.log` inside the container (`podman exec <container> tail -50 /logs/messages.log`).
* **Cannot connect to the database.** The error comes back as `Database error (SQLState ..., code ...): ...`. From inside a container, `localhost` is the container itself.
* **Trying the server without an AI tool.** `jsql-mcp/call.ps1` calls a tool with curl, for example `./jsql-mcp/call.ps1 list_tables '{"schema":"sales"}' -Url http://localhost:9080/mcp -User myuser -Password mypassword`, or `./jsql-mcp/call.ps1 tools/list`.

### Test tool

`jsql-mcp/mcptest/mcptest.py` is a Python command line tool that talks to the server with the official MCP client (Streamable HTTP). It needs Python 3.10 or newer.

```shell
pip install -r jsql-mcp/mcptest/requirements.txt

python jsql-mcp/mcptest/mcptest.py list
python jsql-mcp/mcptest/mcptest.py call list_tables -a schema=sales
python jsql-mcp/mcptest/mcptest.py call execute_query --args '{"sql":"select 1"}'
python jsql-mcp/mcptest/mcptest.py smoke
python jsql-mcp/mcptest/mcptest.py raw @jsql-mcp/req/list.json
```

* `list` shows the tools and their parameters (`--json` for the full definitions).
* `call` runs one tool. Arguments come from `--args` (JSON) and/or repeated `-a key=value`; exit code 1 if the tool reports an error.
* `smoke` checks `/health/live` and `/health/ready`, that a `Bearer` authorization is rejected, that the 12 tools exist, that `server_info`, `list_databases`, `list_schemas` and `list_tables` succeed, and that an unknown tool gives an error. Exit code 1 if any check fails.
* `raw` posts a JSON-RPC body (or `@file`) and prints the HTTP status, headers and body. Use `-H 'Name: value'` and `--no-auth` for protocol level tests.

Options before the subcommand: `--url` (`MCP_URL`, default `http://localhost:9080/mcp`), `--user` (`MCP_USER`), `--password` (`MCP_PASSWORD`) and `--timeout`. Without `--user` no `Authorization` header is sent, so the server's default account is used.

## Databases and JDBC URLs

List of database whose JDBC drivers are included (see [jsql-cli/pom.xml](jsql-cli/pom.xml)) and JDBC connection has been tested.

The database that was tested was most likely running as a container (see the next section for run commands). The sample URL is the URL that was used when testing the connection.

Version info is at the time of testing.


Databases

| Name | Sample JDBC URL<br>(user name/password) | Version | JDBC driver version | Image<br>(more info) |
|---|---|---|---|---|
| DB2/LINUXX8664 | jdbc:db2://127.0.0.1:50000/testdb<br>(db2inst1/passw0rd) | SQL110580 | 4.32.28 | icr.io/db2_community/db2<br>(https://www.ibm.com/docs/en/db2/11.5?topic=deployments-db2-community-edition-docker) |
| PostgreSQL | jdbc:postgresql://localhost:5432/postgres<br>(admin/passw0rd) | 15.4 (Debian 15.4-1.pgdg120+1) | 42.6.0 | docker.io/postgres<br>(https://github.com/docker-library/postgres) |
| Oracle | jdbc:oracle:thin:@localhost:11521:free<br>(system/passw0rd) | Oracle Database 23c Free, Release 23.0.0.0.0 - Developer-Release Version 23.2.0.0.0 | 23.2.0.0.0 | container-registry.oracle.com/database/free:latest<br>(https://www.oracle.com/database/free/get-started/) |
| Informix Dynamic Server | jdbc:informix-sqli://localhost:9088/sysmaster<br>(informix/in4mix) | 14.10.FC9W1DE | 4.50.JC4W1 | icr.io/informix/informix-developer-database<br>(https://github.com/informix/informix-dockerhub-readme) |
| MySQL | jdbc:mysql://127.0.0.1:3306/sampledb<br>(root/passw0rd) | 8.1.0 | mysql-connector-j-8.1.0 (Revision: 7b6f9a337afe6ccb41823df485bf848ca7952b09) | docker.io/mysql<br>(https://hub.docker.com/_/mysql) |
| MariaDB | jdbc:mariadb://127.0.0.1:33306/sampledb<br>(root/passw0rd) | 11.1.2-MariaDB-1:11.1.2+maria~ubu2204 | 3.2.0 | docker.io/mariadb<br>(https://hub.docker.com/_/mariadb) |
| Microsoft SQL Server | jdbc:sqlserver://127.0.0.1:1434;integratedSecurity=false;encrypt=false;trustServerCertificate=false<br>(sa/MySTr0ng@Passw0rd) | 16.00.4065 | 12.4.1.0 | mcr.microsoft.com/mssql/server:2022-latest<br>(https://learn.microsoft.com/en-us/sql/linux/quickstart-install-connect-docker) |
| Presto | jdbc:presto://host:port/catalog/schema | N/A | 0.296 | N/A |

### Database run commands

Run commands for database containers.

* DB2

```
podman run -it --rm -h db2server --name db2server  --privileged=true -p  50000:50000 --env-file files/db2.env  icr.io/db2_community/db2
```

* PostgreSQL

```
podman run -it --rm -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=passw0rd -p 5432:5432 --name postgresql docker.io/postgres
```

* Oracle

```
podman run -it --rm --name oracle  -p 11521:1521 -e ORACLE_PWD=passw0rd -e ORACLE_CHARACTERSET=UTF-8 container-registry.oracle.com/database/free:latest
```

* Informix

```
podman run -it --rm --name ifx -h ifx --privileged -e LICENSE=accept -p 9088:9088 -e STORAGE=local icr.io/informix/informix-developer-database
```

* MySQL

```
podman run -it --rm -e MYSQL_ROOT_PASSWORD=passw0rd -e MYSQL_DATABASE=sampledb -p 3306:3306 docker.io/mysql --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

* MariaDB

```
podman run -it --rm --name mariadb -e MARIADB_ROOT_PASSWORD=passw0rd -e MARIADB_DATABASE=sampledb -p 33306:3306 docker.io/mariadb:latest
```

* Microsoft SQL Server

```
podman run -it --rm -e "ACCEPT_EULA=Y" -e "MSSQL_SA_PASSWORD=MySTr0ng@Passw0rd" -p 1434:1433 --name sql1 --hostname sql1 mcr.microsoft.com/mssql/server:2022-latest
```
