# 工单系统 模块测试方案

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.1 |
| 日期 | 2026-11-16 |
| 测试骨架 | `docs/test-skeletons/`（拷到 `src/test/java/` 下使用） |
| 冒烟脚本 | `scripts/smoke.sh`（应用跑起来后可执行） |

---

## 1. 测试分四层

| 层 | 工具 | 跑得多快 | 测什么 | 何时跑 |
| --- | --- | --- | --- | --- |
| L1 单元 | JUnit 5，无 Spring | 毫秒 | 状态机、纯函数 | 每次改动 |
| L2 切片 | `@MybatisTest` / MockMvc | 秒 | Mapper SQL、Controller 映射 | 每次改动 |
| L3 集成 | `@SpringBootTest` + Testcontainers | 十几秒 | 真实数据库 + 真实登录 + 完整链路 | 提交前 |
| L4 冒烟 | `scripts/smoke.sh`（curl） | 秒 | 部署后“还能不能用” | 部署后 |

**原则**：能在 L1 测的，不要放到 L3。状态机是纯逻辑，必须放在 L1，这样跑 42 个组合也只要几毫秒。

---

## 2. 契约（测试和实现必须一致）

测试写的是行为，行为由契约定义。下面这些东西一旦改动，测试必然失败——**改契约要先改这份表**。

### 2.1 路由

| 方法 | 路径 | 成功时 | 失败时 |
| --- | --- | --- | --- |
| GET | `/login` | 200 | — |
| POST | `/login` | 302 → `/tickets` | 302 → `/login?error` |
| POST | `/logout` | 302 → `/login` | — |
| GET | `/register` | 200 | — |
| POST | `/register` | 302 → `/login` | 200 + 表单错误回显 |
| GET | `/tickets` | 200 | 未登录 302 |
| POST | `/tickets` | 302 → `/tickets/{id}` | 200 + 错误回显 |
| GET | `/tickets/{id}` | 200 | 无权 403 / 不存在 404 |
| POST | `/tickets/{id}/assign` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/accept` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/resolve` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/confirm` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/reject` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/reopen` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/cancel` | 302 → 详情 | 403 / 409 |
| POST | `/tickets/{id}/replies` | 302 → 详情 | 403 / 400 |

### 2.2 表单字段名

| 表单 | 字段 |
| --- | --- |
| 登录 | `username`、`password`、`_csrf` |
| 注册 | `username`、`displayName`、`password`、`confirmPassword`、`_csrf` |
| 新建/编辑工单 | `title`、`description`、`categoryId`、`priority`、`_csrf` |
| 分派 | `assigneeId`、`_csrf` |
| 解决 | `note`、`_csrf` |
| 退回 | `reason`、`_csrf` |
| 回复 | `body`、`visibility`（`PUBLIC` / `INTERNAL`）、`_csrf` |

### 2.3 测试数据约定

- 测试用户统一前缀 `it_`，避免和手工数据打架。
- 测试工单标题统一前缀 `[IT]`。
- 集成测试每个用例前清空 `ticket_event`、`ticket_reply`、`ticket`（不动用户与分类，省时间）。

---

## 3. 各模块测试用例

### M01 框架

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T01-1 | `GET /actuator/health` | 200，`status=UP` |
| T01-2 | 启动日志无异常 | 无 `ERROR` |

### M02 数据库

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T02-1 | `show tables` | 7 张表 |
| T02-2 | 分类种子 | 4 条，名称正确 |
| T02-3 | 重复启动 | 不重复执行迁移、不报错 |

### M03 数据访问（`TicketMapperIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T03-1 | 插入 2 条后 `findAll(1)` | 返回 2 条 |
| T03-2 | `findById` 命中 | 字段完整（标题、状态、优先级） |
| T03-3 | `findById` 不存在的 id | 返回 `null` |
| T03-4 | `count` 与列表条数 | 一致 |

### M04 页面

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T04-1 | `GET /tickets` 有数据 | 200，含标题 |
| T04-2 | 空列表 | 200，含“暂无工单” |
| T04-3 | 访问不存在的详情 | 404，渲染错误页 |

### M05 认证（`TicketAccessIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T05-1 | 未登录 `GET /tickets` | 302 → `/login` |
| T05-2 | 注册后登录 | 302 → `/tickets`；Session 有效 |
| T05-3 | 错误密码 | 302 → `/login?error`，不泄露“用户不存在” |
| T05-4 | 登出后访问 | 302 → `/login` |
| T05-5 | 重复用户名注册 | 200 + 错误提示，用户数不变 |

### M06 授权（`TicketAccessIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T06-1 | 提交人列表 | 只含自己的工单 |
| T06-2 | 处理人列表 | 只含分派给自己的 |
| T06-3 | 管理员列表 | 全部 |
| T06-4 | 提交人访问他人工单详情 | 403，响应与 404 一致 |
| T06-5 | 提交人 POST `/assign` | 403，状态不变 |
| T06-6 | 处理人对他人工单 `accept` | 403 |

### M07 写路径（`TicketFlowIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T07-1 | 创建合法工单 | 302 → 详情；状态 `NEW` |
| T07-2 | 编号格式 | 匹配 `TK-\d{8}-\d{4}` |
| T07-3 | 同一天连续创建 | 序号递增 |
| T07-4 | 标题 4 字 | 200 + 校验错误，不落库 |
| T07-5 | 他人编辑 | 403 |
| T07-6 | `ASSIGNED` 状态编辑 | 409 |

### M08 状态机（`TicketStateMachineTest` + `TicketFlowIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T08-1 | 42 种组合遍历 | 合法集合与转移表完全相等 |
| T08-2 | 删一条合法转移 | 测试变红（手动验证一次） |
| T08-3 | 完整链路 NEW→CLOSED | 每次状态正确，事件数 = 5 |
| T08-4 | 对 `CLOSED` 工单 `accept` | 409，状态不变 |
| T08-5 | 对 `CANCELLED` 工单任何动作 | 409 |
| T08-6 | 事件流水内容 | 含操作人、前后状态、时间 |

### M09 回复与详情（`TicketFlowIT`）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T09-1 | 公开回复 | 提交人与处理人都可见 |
| T09-2 | 内部备注 | 提交人详情页源码中**搜不到正文** |
| T09-3 | 提交人伪造 `visibility=INTERNAL` | 被忽略或 403，不写入 |
| T09-4 | 详情页动作按钮 | 与 `isAllowed` 一致（如 `CLOSED` 不显示“接单”） |
| T09-5 | 版本冲突 | 第二次提交 409，提示“请刷新重试” |

### M10 交付

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| T10-1 | `./mvnw verify` | 全绿 |
| T10-2 | CI | PR 上绿 |
| T10-3 | 干净机器 `docker compose up -d --build` | 健康检查 UP |
| T10-4 | `restart app` | 数据仍在 |
| T10-5 | 仓库扫描 | 无密码/令牌 |

---

## 4. 怎么跑

```bash
./mvnw test                                   # 全部单元 + 切片
./mvnw test -Dtest=TicketStateMachineTest     # 只跑状态机
./mvnw test -Dtest=TicketMapperIT             # 只跑 Mapper
./mvnw test -Dtest=TicketFlowIT               # 只跑完整链路
./mvnw verify                                 # 全部 + 打包校验
BASE=http://localhost:8080 ./scripts/smoke.sh # 部署后冒烟
```

**注意**：`TicketMapperIT`、`TicketFlowIT`、`TicketAccessIT` 需要 Docker 在跑（Testcontainers）。

---

## 5. 失败怎么定位

| 现象 | 大概率原因 | 先看哪里 |
| --- | --- | --- |
| `Invalid bound statement` | XML `namespace`/`id` 与接口不符 | Mapper XML 头部 |
| 期望 302 实际 200 | 表单没带 CSRF，被拦后回显 | 测试是否用 `csrf()` |
| 期望 403 实际 200 | 权限判断写在了模板里 | Service 是否校验归属 |
| `LazyInitialization` | 不存在（MyBatis 无懒加载），多半是 NPE | 视图对象是否漏赋值 |
| 时间断言差 8 小时 | 时区没统一 | 连接串 `connectionTimeZone=UTC` |
| Testcontainers 卡住 | Docker 未启动 | `open -a Docker` |
| 测试单独过、一起挂 | 用例间共享数据 | 检查 `@BeforeEach` 清理 |

---

## 6. 测试骨架怎么用

`docs/test-skeletons/` 下有 4 个文件：

| 文件 | 拷到 | 依赖模块 |
| --- | --- | --- |
| `TicketStateMachineTest.java` | `src/test/java/com/example/ticket/ticket/domain/` | M08 |
| `AbstractIT.java` | `src/test/java/com/example/ticket/support/` | M10 |
| `TicketAccessIT.java` | `src/test/java/com/example/ticket/auth/` | M05、M06 |
| `TicketFlowIT.java` | `src/test/java/com/example/ticket/ticket/` | M07、M08、M09 |

骨架里的断言就是验收标准。**如果实现和断言冲突，先判断哪边错**：需求文档说是对的，就改实现；实现更合理，就改断言并同步改本文档。
