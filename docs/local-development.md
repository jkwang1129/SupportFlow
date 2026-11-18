# 本地运行

准备 JDK 25、Maven 3.9、Python 3.11 或更高版本、Node.js 22 或更高版本，以及 PostgreSQL 17 + pgvector。以下命令从仓库根目录开始，使用 PowerShell。Linux 或 macOS 下可改用 `export` 设置环境变量、用 `.venv/bin/python` 代替 Windows 的虚拟环境解释器。

## 1. 初始化数据库

使用 Docker Compose 时，先设置本地数据库密码及用于所有 Java 服务的同一个 JWT 签名密钥。

```powershell
$env:SUPPORTFLOW_DB_PASSWORD = python -c "import secrets; print(secrets.token_urlsafe(24))"
$env:SUPPORTFLOW_JWT_SECRET = python -c "import secrets; print(secrets.token_urlsafe(48))"
docker compose up -d --wait postgres
```

保留这两个值供后续启动使用。如果已有开发数据卷，继续使用它初始化时的数据库密码。也可以自行准备 PostgreSQL，用 `psql -h 127.0.0.1 -U supportflow -d postgres -f backend/deploy/db-init/db-init.sql` 初始化六库。初始化账号需要建库和安装扩展的权限。使用已有库时，把 `SUPPORTFLOW_DB_HOST`、`SUPPORTFLOW_DB_PORT`、`SUPPORTFLOW_DB_USER` 和 `SUPPORTFLOW_DB_PASSWORD` 设置为实际连接配置。[数据库初始化说明](../backend/deploy/db-init/README.md)

## 2. 启动 Java 服务

让 `JAVA_HOME` 指向 JDK 25，确认 `mvn -v` 显示 Java 25。保留上一步的环境变量。

```powershell
mvn -B -f backend/pom.xml clean package
python scripts/run-backend.py
```

脚本在同一终端管理七个 Java 进程，并把各服务日志写入 `.local/logs/`。用 `Ctrl+C` 停止这次启动的进程。服务端口为 `9090` 到 `9096`，见[后端说明](../backend/README.md)。

如果 `9090` 已被占用，在启动 Java 服务的终端设置 `$env:SUPPORTFLOW_GATEWAY_PORT = '19090'`。启动控制台时设置 `$env:VITE_PROXY_TARGET = 'http://127.0.0.1:19090'`，启动官网预览时设置 `$env:SUPPORTFLOW_API_BASE = 'http://127.0.0.1:19090/api'`。

文件上传和下载需要 S3 兼容存储。在启动 Java 前可运行 `docker compose up -d rustfs`，本地 API 默认为 `127.0.0.1:9000`，客服服务会创建 `supportflow` 存储桶。切换到自己的存储服务时，使用 `SUPPORTFLOW_RUSTFS_ENDPOINT`、`SUPPORTFLOW_RUSTFS_PORT`、`SUPPORTFLOW_RUSTFS_ACCESS_KEY`、`SUPPORTFLOW_RUSTFS_SECRET_KEY`、`SUPPORTFLOW_RUSTFS_BUCKET`、`SUPPORTFLOW_RUSTFS_SECURE`。[RustFS 容器说明](https://docs.rustfs.com/en/installation/container)

## 3. 启动 Python AI 服务

在新终端运行下列命令。把示例文件中的数据库密码换成第 1 步设置的值。连接串中的特殊字符应按 URL 规则编码。

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt -r requirements-ai.txt
Copy-Item .env.example .env
# 编辑 .env 中的 SUPPORTFLOW_AI_DATABASE_URL 和 SUPPORTFLOW_AI_KB_DATABASE_URL 后启动。
.\.venv\Scripts\python -m ai
```

联调真实模型时，在 `.env` 填写供应商密钥，并把 `SUPPORTFLOW_AI_LLM_DEFAULT_PROVIDER` 设置为 `qwen` 或 `deepseek`。仅做启动检查时可只安装 `requirements.txt`，用 `/api/ai/health` 探活。供应商调用和文件解析依赖见 [AI 服务说明](../ai-service/README.md)。

## 4. 创建本地平台管理员

在保留数据库环境变量的终端里，从根目录执行。脚本会提示输入密码，将 BCrypt 哈希和平台管理员角色绑定写入 `user_db`。

```powershell
.\ai-service\.venv\Scripts\python -m pip install bcrypt
.\ai-service\.venv\Scripts\python scripts/create-platform-admin.py --email admin@supportflow.example.com
```

首次登录仍需页面验证码。注册企业账号后，用平台管理员审核，再进入企业控制台配置网站渠道和机器人。知识库上传样本在 [`fixtures/knowledge-base/`](../fixtures/knowledge-base/)。

## 5. 启动控制台与官网

分别在两个终端运行以下命令。

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
```

```powershell
node website/scripts/serve.mjs
```

打开 `http://localhost:5173` 访问控制台，或 `http://localhost:5180` 访问官网。Vite 把 `/api` 代理到 `9090`，把 `/ws` 代理到 `9096`。

## 验证

```powershell
mvn -B -f backend/pom.xml verify
npm --prefix frontend run build
Push-Location ai-service
.\.venv\Scripts\python -m pip install pytest
.\.venv\Scripts\python -m pytest -q
Pop-Location
node website/scripts/build.mjs
```

运行时可检查 `http://localhost:9090/actuator/health`、`http://localhost:9100/api/ai/health`，并从网站渠道发起访客消息，在工作台验证回复与人工接待。模型、附件以及外部渠道需要各自的运行配置。
