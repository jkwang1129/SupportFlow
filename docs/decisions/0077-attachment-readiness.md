# ADR-0077 附件就绪与对象存储故障

客服服务新增管理探测 `/actuator/attachmentreadiness`。应用接受请求且原有数据库就绪检查通过后，用业务 S3 客户端发起只读 `HeadBucket`。对象存储调用总预算为 2 秒，单次尝试预算为 1 秒。探测只返回 `UP` 或 `DOWN`，对应 HTTP 200 或 503。通用健康与就绪继续供只依赖数据库的聊天服务使用。

存储客户端将连接失败、超时、桶不存在、凭据被拒绝、限流和服务端错误归为依赖不可用。已授权的附件操作收到 HTTP 503 和业务码 `50308`，响应禁止缓存。读取请求带 `Retry-After: 5`，写入请求由客户端按原业务流程决定是否重试。对象缺失和权限校验分别返回各自的业务结果。故障分类由实际对象存储操作触发。

部署时，通用流量继续使用 `/actuator/health/readiness`。依赖附件的流量或专项告警可以使用 `/actuator/attachmentreadiness`。S3 兼容账号须允许对目标桶发起 `HeadBucket`。该操作验证桶访问，实际读取权限和文件完整性由业务回归检查。[S3 HeadBucket 接口](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadBucket.html)

复现使用 `python scripts/test-database-restore.py --with-attachments --with-services`。恢复夹具暂时暂停独立目标 RustFS 容器，检查三种客服探测、AI 就绪、登录坐席的聊天记录、聊天附件和头像。恢复容器后复验对象字节、MIME 与权限，再复核六库和存储桶状态。客户端使用本次演练显式配置的 1 秒连接、读和写入参数。探测时间和业务恢复时间分开记录。

回退时撤下附件监测并重新部署上一版客服镜像即可，数据库版本沿用当前值。此次演练验证合成停机下的本地读路径。生产流量切换与写结果不确定时的核销待验收。
