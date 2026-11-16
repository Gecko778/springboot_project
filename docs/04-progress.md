# 工单系统 进度看板

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.1 |
| 日期 | 2026-11-16 |
| 用法 | 每完成一个模块，把状态改成 ✅，并把测试结论填进第 4 节 |

状态约定：⬜ 未开始 · 🟡 进行中 · ✅ 完成 · ⛔ 阻塞（写明卡在哪）

---

## 1. 总体进度

| 模块 | 名称 | 状态 | 对应阶段 | 测试文件 |
| --- | --- | --- | --- | --- |
| M01 | 框架搭建 | ⬜ | P1 | `HealthTest`、`curl /actuator/health` |
| M02 | 数据库与迁移 | ⬜ | P1 | 冒烟脚本第 2 步 |
| M03 | 数据访问（Mapper） | ⬜ | P1 | `TicketMapperIT` |
| M04 | 页面骨架与路由 | ⬜ | P1 | 冒烟脚本第 3 步 |
| M05 | 认证（登录/注册） | ⬜ | P2 | `TicketAccessIT` |
| M06 | 授权（角色与范围） | ⬜ | P2 | `TicketAccessIT` |
| M07 | 工单写路径 | ⬜ | P3 | `TicketFlowIT` |
| M08 | 状态机与流转 | ⬜ | P3 | `TicketStateMachineTest`、`TicketFlowIT` |
| M09 | 回复与详情 | ⬜ | P3 | `TicketFlowIT` |
| M10 | 质量与交付 | ⬜ | P4 | `./mvnw verify`、CI |

---

## 2. 模块明细

每个模块都按同一结构写：**要做什么 → 你要写的文件 → 参考哪一节 → 做完跑什么 → 通过标准**。

### M01 框架搭建

**目标**：一个能启动、有健康检查的空工程。

| 你要写 | 说明 |
| --- | --- |
| 工程骨架 | Initializr 生成（`web`、`thymeleaf`、`validation`、`security`、`actuator`、`devtools`） |
| `application.yaml` | 应用名、端口、Actuator 暴露（数据源配置在 M02） |
| 启动类 | 默认生成即可 |
| `HealthTest` | `GET /actuator/health` → 200、`status=UP` |

**参考**：`M01-框架搭建教程.md`；`03-build-guide.md` §1.1（仅工程生成）

**做完跑**：`curl -s localhost:8080/actuator/health`；`./mvnw test`

**通过标准**：health 返回 `status=UP`；`Tests run: 2, Failures: 0`；启动日志无 `ERROR`。

> `scripts/smoke.sh --only=health` 还断言未登录访问 `/tickets` 返回 302。M01 只有默认安全配置时 curl 实际是 401，这条要等 M05 配好表单登录才成立；M01 不要拿整个脚本当门禁。

---

### M02 数据库与迁移

**目标**：7 张表由 Flyway 建出来，包含种子数据。

| 你要写 | 说明 |
| --- | --- |
| 依赖与配置 | `spring-boot-starter-flyway`、`flyway-mysql`、MySQL 驱动、`spring.datasource.*` |
| `compose.yaml` | MySQL 8.4 |
| `V1__init.sql` | 7 张表，原样复制 `02-design.md` 2.2 |
| `V2__seed.sql` | org + 4 个分类 |

**参考**：`03-build-guide.md` 1.2–1.4

**做完跑**：`scripts/smoke.sh --only=db`（检查 7 张表 + 分类存在）

**通过标准**：`show tables` 正好 7 张；分类 ≥4 条；重启应用不报迁移错误。

---

### M03 数据访问

**目标**：能用 Mapper 从数据库读出工单。

| 你要写 | 说明 |
| --- | --- |
| 5 个实体类 | `Ticket`、`User`、`Category`、`TicketReply`、`TicketEvent` |
| `TicketMapper` + XML | `findAll`、`findById`、`count` |
| （可选）`CategoryMapper` | 给表单下拉用 |

**参考**：`03-build-guide.md` 1.5

**做完跑**：`./mvnw test -Dtest=TicketMapperIT`

**通过标准**：插入 2 条数据能查出来；`count` 与 `findAll` 条数一致。

---

### M04 页面骨架与路由

**目标**：浏览器能打开列表页，格式错误时有统一错误页。

| 你要写 | 说明 |
| --- | --- |
| `layout.html` | 导航、消息区、内容块 |
| `tickets/list.html` | 表格 + 空态提示 |
| `error.html` | 错误码 + 人话提示 |
| `TicketController#list` | 查数据、塞 Model、返回视图名 |

**参考**：`03-build-guide.md` 1.7

**做完跑**：冒烟脚本第 3 步

**通过标准**：`GET /tickets` 200；页面包含种子工单标题；空列表时显示“暂无工单”而不是报错。

---

### M05 认证

**目标**：未登录跳登录页；能注册、能登录、能登出。

| 你要写 | 说明 |
| --- | --- |
| `SecurityConfig` | 表单登录、放行 `/login`、`/register`、静态资源 |
| `DbUserDetailsService` | 从 `app_user` 读用户 |
| `RegisterController` + 模板 | 用户名唯一、密码 ≥8 位 |
| `DataInitializer` | 启动时创建 `admin` |

**参考**：`03-build-guide.md` 2.1–2.3

**做完跑**：`./mvnw test -Dtest=TicketAccessIT#未登录访问列表_应跳转登录页`

**通过标准**：未登录 302 → `/login`；注册后能登录；登出后再访问 → 又跳登录。

---

### M06 授权

**目标**：三种角色看到的数据不同；越权被后端拦。

| 你要写 | 说明 |
| --- | --- |
| 列表范围过滤 | `REPORTER` 自己 / `AGENT` 分派给自己 / `ADMIN` 全部 |
| 详情归属校验 | 无权访问 → 403，且内容与“不存在”一致 |
| `/admin/users` | 改角色、停用 |

**参考**：`03-build-guide.md` 2.4–2.5

**做完跑**：`./mvnw test -Dtest=TicketAccessIT`

**通过标准**：三个角色的可见条数符合预期；提交人访问他人详情 403；提交人 POST `/assign` 403。

---

### M07 工单写路径

**目标**：能创建、编辑、撤销工单，编号正确。

| 你要写 | 说明 |
| --- | --- |
| `CreateTicketForm` + 校验 | 标题 5–100、描述 10–2000、分类必填 |
| `TicketService#create` | 取号 + 插入 + 写事件 |
| `TicketSeqMapper` | `on duplicate key update` 取号 |
| 编辑 / 撤销 | 仅 `NEW` 可编辑；`NEW`/`ASSIGNED` 可撤销 |

**参考**：`03-build-guide.md` 3.1–3.2、`02-design.md` 3.2

**做完跑**：`./mvnw test -Dtest=TicketFlowIT#创建工单_编号格式正确`

**通过标准**：编号形如 `TK-20261116-0001`；同一天连续创建序号递增；编辑越权返回 403。

---

### M08 状态机与流转

**目标**：7 个动作可用，非法跳转被拒，每次变更留事件。

| 你要写 | 说明 |
| --- | --- |
| `TicketStateMachineTest` | 先写测试（42 种组合） |
| `TicketStateMachine` | 转移表 + `require` / `isAllowed` |
| 7 个动作端点 | 五步法：查 → 归属 → 状态 → 角色 → 改 + 记 |
| `TicketEventMapper` | 事件落库 |

**参考**：`02-design.md` 第 3 节

**做完跑**：`./mvnw test -Dtest='TicketStateMachineTest,TicketFlowIT'`

**通过标准**：42 测试全绿；删掉任意一条转移测试必须变红；对 `CLOSED` 工单 `accept` 返回 409。

---

### M09 回复与详情

**目标**：公开回复和内部备注都可用，内部备注对提交人不可见，详情页完整。

| 你要写 | 说明 |
| --- | --- |
| 回复端点 | `POST /tickets/{id}/replies` |
| 内部备注可见性 | 查询 SQL 带 `visibility='PUBLIC'` 条件 |
| 详情页 | 字段 + 动作按钮 + 回复流 + 时间线 |
| 乐观锁 | 版本冲突 → 409 + 提示 |

**参考**：`02-design.md` 5、6.2、7

**做完跑**：冒烟脚本第 4–6 步 + `TicketFlowIT`

**通过标准**：提交人页面源码里搜不到内部备注正文；两窗口并发接单一个 409。

---

### M10 质量与交付

**目标**：测试可复现、CI 绿、干净机器一条命令起。

| 你要写 | 说明 |
| --- | --- |
| Testcontainers 基类 | 见 `docs/test-skeletons/AbstractIT.java` |
| 集成测试 | `TicketFlowIT`、`TicketAccessIT` |
| `.github/workflows/ci.yml` | `./mvnw -B verify` |
| `Dockerfile` | 多阶段构建 |
| `deploy/compose.yaml` | 生产编排 |
| `README.md` | 怎么跑、演示账号 |

**参考**：`03-build-guide.md` 第 4 节

**做完跑**：`./mvnw verify` + CI + `docker compose up -d --build`

**通过标准**：全绿；部署后 `{"status":"UP"}`；`restart app` 后数据还在。

---

## 3. 我们的协作方式

1. 你按模块顺序写，一次只写一个模块。
2. 写完后告诉我：**“M0X 写完了”**，并说明你改了哪些文件。
3. 我做三件事：跑该模块对应的测试 → 读你的实现 → 给结论（通过 / 哪一行有问题 / 下一步）。
4. 测试不过时，我先判断是**测试写错**还是**实现写错**，不会让你改测试来迁就实现。
5. 你没写完但卡住了，把报错全文贴出来，我们定位到具体一行再动。

**模块之间不跳号**：M06 没通过不要开始 M07，否则权限判断会散落到后面的每个端点里。

---

## 4. 测试记录

每通过一个模块，追加一行。

| 日期 | 模块 | 跑的测试 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |

---

## 5. 当前卡点

| 卡点 | 影响模块 | 需要谁决定 |
| --- | --- | --- |
| 认证方式 Session 待最终确认 | M05 | 你 |
| 启动 M02 前需安装 Docker | M02 | 你（一条命令的事） |
