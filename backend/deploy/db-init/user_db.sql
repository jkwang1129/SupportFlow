-- user_db 初始化脚本（基线 + 增量），生成时间：2026-10-09 18:23
-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。
-- ===========================================================================
-- 库：user_db   （基线 schema/user_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · user_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "sys_user" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16)  NOT NULL,
  "user_no" VARCHAR(32)  NOT NULL,
  "name" VARCHAR(64)  NOT NULL,
  "phone" VARCHAR(20)  DEFAULT NULL,
  "email" VARCHAR(128) DEFAULT NULL,
  "password" VARCHAR(128) DEFAULT NULL,
  "avatar" VARCHAR(512) DEFAULT NULL,
  "user_type" SMALLINT  NOT NULL,
  "status" SMALLINT  NOT NULL DEFAULT 1,
  "last_login_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64)  DEFAULT NULL,
  "editor" VARCHAR(64)  DEFAULT NULL,
  "is_deleted" BOOLEAN   NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_user_user_type CHECK ("user_type" IN (1,2)),
  CONSTRAINT ck_sys_user_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_user_no" UNIQUE ("user_no")
);
COMMENT ON TABLE "sys_user" IS '用户表';
COMMENT ON COLUMN "sys_user"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user"."tenant_code" IS '主归属租户；平台账号为 PLATFORM，新注册企业账号审核开通前挂 PLATFORM，开通后回填正式 T 编码';
COMMENT ON COLUMN "sys_user"."user_no" IS '用户编号（对外）';
COMMENT ON COLUMN "sys_user"."name" IS '姓名';
COMMENT ON COLUMN "sys_user"."phone" IS '手机号（脱敏存储）';
COMMENT ON COLUMN "sys_user"."email" IS '邮箱';
COMMENT ON COLUMN "sys_user"."password" IS '密码哈希（BCrypt），SSO 账号可为空';
COMMENT ON COLUMN "sys_user"."avatar" IS '头像URL';
COMMENT ON COLUMN "sys_user"."user_type" IS '类型码：1-平台账号、2-企业账号';
COMMENT ON COLUMN "sys_user"."status" IS '状态码：1-正常、2-停用、3-锁定';
COMMENT ON COLUMN "sys_user"."last_login_time" IS '最近登录时间';
CREATE INDEX "idx_sys_user_tenant_status" ON "sys_user" ("tenant_code", "status");

CREATE TABLE "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);
COMMENT ON TABLE "tenant_member" IS '企业成员关系表';
COMMENT ON COLUMN "tenant_member"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "tenant_member"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "tenant_member"."user_id" IS '用户ID';
COMMENT ON COLUMN "tenant_member"."join_time" IS '加入时间';
COMMENT ON COLUMN "tenant_member"."status" IS '状态码：1-在职、2-已退出';
CREATE INDEX "idx_tenant_member_user_id" ON "tenant_member" ("user_id");

CREATE TABLE "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN  NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN  NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);
COMMENT ON TABLE "sys_role" IS '角色表';
COMMENT ON COLUMN "sys_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role"."role_code" IS '角色编码';
COMMENT ON COLUMN "sys_role"."role_name" IS '角色名称';
COMMENT ON COLUMN "sys_role"."role_type" IS '类型码：1-平台角色、2-企业角色';
COMMENT ON COLUMN "sys_role"."is_fixed" IS '是否内置固定角色';
COMMENT ON COLUMN "sys_role"."is_enabled" IS '是否启用';
COMMENT ON COLUMN "sys_role"."remark" IS '备注';

CREATE TABLE "sys_permission" (
  "id" BIGINT NOT NULL,
  "perm_code" VARCHAR(64)  NOT NULL,
  "perm_name" VARCHAR(64)  NOT NULL,
  "perm_type" SMALLINT  NOT NULL,
  "parent_id" BIGINT DEFAULT 0,
  "sort_no" INT DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_permission_perm_type CHECK ("perm_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_perm_code" UNIQUE ("perm_code")
);
COMMENT ON TABLE "sys_permission" IS '权限点/菜单表';
COMMENT ON COLUMN "sys_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_permission"."perm_code" IS '权限编码（对应菜单/功能）';
COMMENT ON COLUMN "sys_permission"."perm_name" IS '权限名称';
COMMENT ON COLUMN "sys_permission"."perm_type" IS '类型码：1-菜单、2-按钮、3-接口';
COMMENT ON COLUMN "sys_permission"."parent_id" IS '父权限ID';
COMMENT ON COLUMN "sys_permission"."sort_no" IS '排序';

CREATE TABLE "sys_role_permission" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_id" BIGINT NOT NULL,
  "perm_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_role_perm" UNIQUE ("role_id", "perm_id")
);
COMMENT ON TABLE "sys_role_permission" IS '角色权限关联表';
COMMENT ON COLUMN "sys_role_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role_permission"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role_permission"."role_id" IS '角色ID';
COMMENT ON COLUMN "sys_role_permission"."perm_id" IS '权限ID';
CREATE INDEX "idx_sys_role_permission_perm_id" ON "sys_role_permission" ("perm_id");

CREATE TABLE "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);
COMMENT ON TABLE "sys_user_role" IS '用户角色关联表';
COMMENT ON COLUMN "sys_user_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user_role"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "sys_user_role"."user_id" IS '用户ID';
COMMENT ON COLUMN "sys_user_role"."role_id" IS '角色ID';
CREATE INDEX "idx_sys_user_role_role_id" ON "sys_user_role" ("role_id");

CREATE TABLE "login_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "login_type" SMALLINT NOT NULL,
  "ip" VARCHAR(64) DEFAULT NULL,
  "device" VARCHAR(128) DEFAULT NULL,
  "browser" VARCHAR(64) DEFAULT NULL,
  "region" VARCHAR(64) DEFAULT NULL,
  "result" SMALLINT NOT NULL,
  "login_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_login_log_login_type CHECK ("login_type" IN (1,2,3)),
  CONSTRAINT ck_login_log_result CHECK ("result" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "login_log" IS '登录记录表';
COMMENT ON COLUMN "login_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "login_log"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "login_log"."user_id" IS '用户ID';
COMMENT ON COLUMN "login_log"."login_type" IS '登录方式码：1-密码、2-企业微信SSO、3-飞书SSO';
COMMENT ON COLUMN "login_log"."ip" IS '登录IP';
COMMENT ON COLUMN "login_log"."device" IS '设备信息';
COMMENT ON COLUMN "login_log"."browser" IS '浏览器';
COMMENT ON COLUMN "login_log"."region" IS '登录地区';
COMMENT ON COLUMN "login_log"."result" IS '结果码：1-成功、2-失败、3-异常';
COMMENT ON COLUMN "login_log"."login_time" IS '登录时间';
CREATE INDEX "idx_login_log_tenant_user_time" ON "login_log" ("tenant_code", "user_id", "login_time");

CREATE TABLE "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);
COMMENT ON TABLE "member_invite" IS '成员邀请表';
COMMENT ON COLUMN "member_invite"."invite_code" IS '邀请码';
COMMENT ON COLUMN "member_invite"."inviter_id" IS '邀请人用户ID';
COMMENT ON COLUMN "member_invite"."role_id" IS '受邀默认角色ID';
COMMENT ON COLUMN "member_invite"."status" IS '状态码：1-有效、2-已使用、3-已失效';
COMMENT ON COLUMN "member_invite"."used_by" IS '使用人用户ID';
COMMENT ON COLUMN "member_invite"."used_time" IS '使用时间';
COMMENT ON COLUMN "member_invite"."expire_time" IS '过期时间';
CREATE INDEX "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/user_db_member_invite.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · user_db 成员邀请增量脚本（PostgreSQL 17）
-- 用途：在已按注册/登录教程建库的本地环境补齐邀请客服成员所需表。
-- 幂等：可重复执行，不会与已存在的表冲突。

CREATE TABLE IF NOT EXISTS "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);

CREATE TABLE IF NOT EXISTS "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);

CREATE TABLE IF NOT EXISTS "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);

CREATE TABLE IF NOT EXISTS "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);

CREATE INDEX IF NOT EXISTS "idx_tenant_member_user_id" ON "tenant_member" ("user_id");
CREATE INDEX IF NOT EXISTS "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/user_db_platform.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · user_db 平台账号中心与角色权限（PostgreSQL 17）
-- 用途：把「平台账号中心（用户管理）」与「平台角色 + 菜单权限」跑起来。
--
-- 说明：sys_role / sys_permission / sys_role_permission / sys_user_role 四张表在最初的
--      user_db.sql 里就建好了，但一直没人用。这里只补**数据**（权限目录 + 平台内置角色 +
--      角色权限绑定 + 给还没有角色的平台账号兜底绑定管理员角色），不改表结构。
--
-- 幂等：可重复执行（统一用 ON CONFLICT DO NOTHING，避免 PK 与唯一键两种冲突）。
--
-- 权限口径：perm_code 直接等于**前端路由 path**（如 /platform/tenants）。
--   这样前端菜单只要拿 route.path 去比对就能决定显不显示，不用再维护一张映射表，
--   也不会出现「权限改了、菜单没跟着改」的错位。perm_type：1-菜单、2-按钮、3-接口
--   （本期只落菜单，按钮/接口级权限预留）。

-- ---------------------------------------------------------------------------
-- 1. 平台菜单权限目录
-- ---------------------------------------------------------------------------
INSERT INTO "sys_permission" ("id", "perm_code", "perm_name", "perm_type", "parent_id",
                              "sort_no", "is_enabled", "creator")
VALUES
  (960000000000000001, '/admin/reviews',     '企业审核',   1, 0, 10, TRUE, 'system'),
  (960000000000000002, '/platform/support',  '支持工单',   1, 0, 20, TRUE, 'system'),
  (960000000000000003, '/platform/tenants',  '租户管理',   1, 0, 30, TRUE, 'system'),
  (960000000000000004, '/platform/billing',  '计费与账单', 1, 0, 40, TRUE, 'system'),
  (960000000000000005, '/platform/payments', '收款流水',   1, 0, 50, TRUE, 'system'),
  (960000000000000006, '/platform/users',    '用户管理',   1, 0, 60, TRUE, 'system'),
  (960000000000000007, '/platform/roles',    '角色权限',   1, 0, 70, TRUE, 'system'),
  (960000000000000008, '/platform/audit',    '审计日志',   1, 0, 80, TRUE, 'system'),
  (960000000000000009, '/platform/settings', '平台设置',   1, 0, 90, TRUE, 'system'),
  (960000000000000010, '/platform/sitecfg',  '官网配置',   1, 0, 100, TRUE, 'system'),
  (960000000000000011, '/platform/analytics','平台数据',   1, 0, 110, TRUE, 'system'),
  (960000000000000012, '/platform/aimon',    'AI 监控',    1, 0, 120, TRUE, 'system'),
  (960000000000000013, '/platform/cs',       '客户成功',   1, 0, 130, TRUE, 'system'),
  (960000000000000014, '/platform/sec',      '安全中心',   1, 0, 140, TRUE, 'system'),
  (960000000000000015, '/platform/observe',  '可观测',     1, 0, 150, TRUE, 'system')
ON CONFLICT DO NOTHING;

-- 名字/排序可能被改过，这里只把「权限名称与排序」对齐（不动启用状态，运营可以自己停用）
UPDATE "sys_permission" p
   SET "perm_name" = v.perm_name,
       "sort_no"   = v.sort_no,
       "is_enabled" = TRUE,
       "update_time" = CURRENT_TIMESTAMP
  FROM (VALUES
        ('/admin/reviews',     '企业审核',   10),
        ('/platform/support',  '支持工单',   20),
        ('/platform/tenants',  '租户管理',   30),
        ('/platform/billing',  '计费与账单', 40),
        ('/platform/payments', '收款流水',   50),
        ('/platform/users',    '用户管理',   60),
        ('/platform/roles',    '角色权限',   70),
        ('/platform/audit',    '审计日志',   80),
        ('/platform/settings', '平台设置',   90),
        ('/platform/sitecfg',  '官网配置',   100),
        ('/platform/analytics','平台数据',   110),
        ('/platform/aimon',    'AI 监控',    120),
        ('/platform/cs',       '客户成功',   130),
        ('/platform/sec',      '安全中心',   140),
        ('/platform/observe',  '可观测',     150)
       ) AS v(perm_code, perm_name, sort_no)
 WHERE p."perm_code" = v.perm_code
   AND p."is_deleted" = FALSE;

-- ---------------------------------------------------------------------------
-- 2. 平台内置角色（tenant_code 固定 PLATFORM，role_type=1 平台角色）
-- ---------------------------------------------------------------------------
INSERT INTO "sys_role" ("id", "tenant_code", "role_code", "role_name", "role_type",
                        "is_fixed", "is_enabled", "remark", "creator")
VALUES
  (970000000000000001, 'PLATFORM', 'PLATFORM_ADMIN',   '平台管理员', 1, TRUE, TRUE,
   '内置：拥有全部平台菜单权限', 'system'),
  (970000000000000002, 'PLATFORM', 'PLATFORM_OPS',     '平台运营',   1, FALSE, TRUE,
   '企业审核 / 支持工单 / 租户管理 / 用户管理', 'system'),
  (970000000000000003, 'PLATFORM', 'PLATFORM_FINANCE', '平台财务',   1, FALSE, TRUE,
   '计费与账单 / 收款流水', 'system')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 角色权限绑定
-- ---------------------------------------------------------------------------
-- 平台管理员 = 全部权限：**先清后插**（不是"插一次就不管"）。
--
-- 为什么必须这么写：绑定行的主键原来是按 row_number 生成的固定 ID，后来加了一条新权限，
-- 排序一变，所有 ID 都错位；重跑脚本时 PK 冲突被 ON CONFLICT DO NOTHING 吞掉，
-- 新权限就永远绑不上去（表现为"内置管理员少了刚加的那个菜单"）。
-- 内置角色的权限本来就不可编辑（PlatformRoleService 里明确拒绝改内置角色权限），
-- 所以这里每次都同步成"全集"是正确语义，也顺带解决了新增权限自动跟进。
DELETE FROM "sys_role_permission"
 WHERE "role_id" IN (SELECT "id" FROM "sys_role"
                      WHERE "tenant_code" = 'PLATFORM' AND "role_code" = 'PLATFORM_ADMIN');

INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 980000000000000000 + row_number() OVER (ORDER BY p."sort_no", p."id"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE AND p."is_enabled" = TRUE
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_ADMIN';

-- 平台运营 / 平台财务：**只在"这个角色一条权限都没有"时初始化**。
-- 这两个不是内置角色，运营可以在页面上改权限；脚本不该把人家改过的权限又刷回去，
-- 所以只在首次（还是空的）时给一套默认值，之后完全尊重页面上的配置。
INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 981000000000000000 + row_number() OVER (ORDER BY p."perm_code"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE
   AND p."perm_code" IN ('/admin/reviews', '/platform/support', '/platform/tenants',
                         '/platform/users', '/platform/sitecfg', '/platform/analytics',
                         '/platform/aimon', '/platform/cs', '/platform/sec', '/platform/observe')
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_OPS'
   AND NOT EXISTS (SELECT 1 FROM "sys_role_permission" x WHERE x."role_id" = r."id")
ON CONFLICT DO NOTHING;

INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 982000000000000000 + row_number() OVER (ORDER BY p."perm_code"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE
   AND p."perm_code" IN ('/platform/billing', '/platform/payments')
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_FINANCE'
   AND NOT EXISTS (SELECT 1 FROM "sys_role_permission" x WHERE x."role_id" = r."id")
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 兜底：还没有任何平台角色的平台账号，绑到「平台管理员」
--    为什么要有这一步：菜单是按权限过滤的，如果历史平台账号没有角色，
--    一升级上来就会「一个菜单都看不到」——把自己锁在门外。
--    这条只对「当前没有任何角色」的账号生效，已经分好角色的不会被改。
-- ---------------------------------------------------------------------------
INSERT INTO "sys_user_role" ("id", "tenant_code", "user_id", "role_id")
SELECT 990000000000000000 + row_number() OVER (ORDER BY u."id"), 'PLATFORM', u."id", r."id"
  FROM "sys_user" u
  JOIN "sys_role" r ON r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_ADMIN'
 WHERE u."user_type" = 1
   AND u."is_deleted" = FALSE
   AND NOT EXISTS (SELECT 1 FROM "sys_user_role" ur
                    WHERE ur."user_id" = u."id" AND ur."tenant_code" = 'PLATFORM')
ON CONFLICT DO NOTHING;
