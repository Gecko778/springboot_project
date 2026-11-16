# 工单系统 技术设计（MVP）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v0.2 |
| 日期 | 2026-11-16 |
| 对应需求 | `01-requirements.md` v0.2 |
| 技术栈变更 | v0.2 起：MyBatis + MySQL（替代 JPA + PostgreSQL） |

---

## 1. 架构总览

```
浏览器
  │  HTTP（表单提交 / 页面跳转）
  ▼
Spring Security 过滤器链        ← 登录、CSRF、会话
  ▼
Controller（web 层）           ← 参数绑定、表单校验、选模板
  ▼
Service（应用层）              ← 事务边界、权限校验、状态机调用
  ▼
Mapper（持久层，MyBatis）       ← 接口 + XML 里的 SQL
  ▼
MySQL 8.4
```

### 1.1 为什么选 MyBatis + MySQL

- SQL 写在 XML 里，看得见、改得动，出问题能直接在数据库里复现；
- 国内 Java 后端岗位大量使用这套组合，学习迁移成本低；
- 没有“魔法”：不会出现 JPA 那种“看起来只查一张表、实际发了几十条 SQL”的情况；
- 代价是手写 SQL 更多，分页、关联查询都要自己写。

### 1.2 分层规则

| 规则 | 说明 |
| --- | --- |
| Controller 不碰 Mapper | 只能调 Service |
| Service 是事务边界 | `@Transactional` 只出现在 Service 的公开方法上 |
| 权限校验在 Service | Controller 只负责把参数交出去 |
| 领域规则不用 Spring 注解 | 状态机、枚举是纯 Java，能被单元测试直接 new 出来 |
| 模板只拿视图对象 | 不把数据库实体直接丢给 Thymeleaf |
| 数据库结构只由 Flyway 改 | 没有 `ddl-auto`，建表语句就是唯一事实 |
| SQL 只出现在 Mapper XML | 不在 Java 里拼 SQL 字符串 |

### 1.3 包结构

```
com.example.ticket
├── TicketApplication.java        （Mapper 接口逐个标 @Mapper，不用 @MapperScan）
├── common/
│   ├── ErrorCode.java
│   ├── BusinessException.java
│   ├── GlobalExceptionHandler.java
│   └── PageResult.java
├── auth/
│   ├── SecurityConfig.java
│   ├── DbUserDetailsService.java
│   └── CurrentUser.java
├── user/
│   ├── User.java
│   ├── Role.java
│   ├── UserMapper.java
│   ├── UserService.java
│   └── RegisterController.java
└── ticket/
    ├── domain/
    │   ├── Ticket.java
    │   ├── TicketStatus.java
    │   ├── TicketAction.java
    │   ├── TicketStateMachine.java   ← 唯一的状态流转入口
    │   └── Priority.java
    ├── mapper/
    │   ├── TicketMapper.java
    │   ├── TicketReplyMapper.java
    │   ├── TicketEventMapper.java
    │   └── TicketSeqMapper.java
    ├── TicketService.java
    ├── TicketController.java
    └── web/
        ├── CreateTicketForm.java
        ├── TicketListItem.java
        └── TicketDetailView.java
```

Mapper XML 放在 `src/main/resources/mapper/`，文件名与接口同名。

---

## 2. 数据模型

### 2.1 表清单

| 表 | 作用 |
| --- | --- |
| `org` | 组织。MVP 只有一行，但所有业务表都带 `org_id` |
| `app_user` | 用户与角色 |
| `category` | 工单分类（MVP 用种子数据） |
| `ticket` | 工单主表 |
| `ticket_reply` | 回复与内部备注 |
| `ticket_event` | 事件流水，时间线与审计的唯一来源 |
| `ticket_day_seq` | 按天分配工单序号 |

统一约定：`InnoDB`、`utf8mb4`（要能存 emoji）、时间用 `datetime(6)`（毫秒精度，存 UTC）。

### 2.2 建表脚本（Flyway `V1__init.sql`）

```sql
create table org (
    id         bigint       not null auto_increment,
    name       varchar(100) not null,
    created_at datetime(6)  not null default current_timestamp(6),
    primary key (id)
) engine = InnoDB default charset = utf8mb4;

create table app_user (
    id            bigint       not null auto_increment,
    org_id        bigint       not null,
    username      varchar(32)  not null,
    password_hash varchar(100) not null,
    display_name  varchar(50)  not null,
    role          varchar(20)  not null,
    enabled       tinyint(1)   not null default 1,
    created_at    datetime(6)  not null default current_timestamp(6),
    primary key (id),
    unique key uk_user_username (org_id, username),
    key idx_user_role (org_id, role),
    constraint fk_user_org foreign key (org_id) references org (id),
    constraint ck_user_role check (role in ('REPORTER', 'AGENT', 'ADMIN'))
) engine = InnoDB default charset = utf8mb4;

create table category (
    id         bigint      not null auto_increment,
    org_id     bigint      not null,
    name       varchar(50) not null,
    sort_order int         not null default 0,
    active     tinyint(1)  not null default 1,
    primary key (id),
    unique key uk_category_name (org_id, name),
    constraint fk_category_org foreign key (org_id) references org (id)
) engine = InnoDB default charset = utf8mb4;

create table ticket (
    id          bigint       not null auto_increment,
    org_id      bigint       not null,
    code        varchar(20)  not null,
    title       varchar(100) not null,
    description text         not null,
    category_id bigint       not null,
    priority    varchar(10)  not null,
    status      varchar(20)  not null,
    reporter_id bigint       not null,
    assignee_id bigint       null,
    version     int          not null default 0,
    created_at  datetime(6)  not null default current_timestamp(6),
    updated_at  datetime(6)  not null default current_timestamp(6),
    resolved_at datetime(6)  null,
    closed_at   datetime(6)  null,
    primary key (id),
    unique key uk_ticket_code (org_id, code),
    key idx_ticket_status (org_id, status, updated_at),
    key idx_ticket_assignee (org_id, assignee_id, status),
    key idx_ticket_reporter (org_id, reporter_id, updated_at),
    constraint fk_ticket_org foreign key (org_id) references org (id),
    constraint fk_ticket_category foreign key (category_id) references category (id),
    constraint ck_ticket_priority check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    constraint ck_ticket_status check (status in ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'))
) engine = InnoDB default charset = utf8mb4;

create table ticket_reply (
    id         bigint      not null auto_increment,
    ticket_id  bigint      not null,
    author_id  bigint      not null,
    body       text        not null,
    visibility varchar(10) not null,
    created_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    key idx_reply_ticket (ticket_id, created_at),
    constraint fk_reply_ticket foreign key (ticket_id) references ticket (id),
    constraint ck_reply_visibility check (visibility in ('PUBLIC', 'INTERNAL'))
) engine = InnoDB default charset = utf8mb4;

create table ticket_event (
    id          bigint      not null auto_increment,
    ticket_id   bigint      not null,
    actor_id    bigint      not null,
    action      varchar(20) not null,
    from_status varchar(20) null,
    to_status   varchar(20) null,
    detail      json        null,
    created_at  datetime(6) not null default current_timestamp(6),
    primary key (id),
    key idx_event_ticket (ticket_id, created_at),
    constraint fk_event_ticket foreign key (ticket_id) references ticket (id)
) engine = InnoDB default charset = utf8mb4;

create table ticket_day_seq (
    seq_date date not null,
    last_no  int  not null,
    primary key (seq_date)
) engine = InnoDB default charset = utf8mb4;
```

> 外键约束在生产环境常被省略（便于分库分表），但本项目保留：它能在开发期帮你挡住一半的数据错误。

### 2.3 写库约定

- 时间存 `datetime(6)`，值为 UTC。JDBC 连接串带 `connectionTimeZone=UTC`，这样 `current_timestamp(6)` 和 Java 侧的 `LocalDateTime` 都是 UTC，不发生隐式转换。
- 展示时再转本地时区，**在 Service 层格式化好放进视图对象**，模板只负责输出。
- 枚举存字符串（`varchar` + `check`），MyBatis 用默认的 `EnumTypeHandler`，读写都是枚举名。
- 列名 `snake_case`，Java 字段 `camelCase`，靠 `map-underscore-to-camel-case: true` 自动映射。

---

## 3. 状态机

### 3.1 转移表（代码里的唯一事实）

| 动作 | 起始状态 | 目标状态 | 谁能做 |
| --- | --- | --- | --- |
| `ASSIGN` | `NEW` | `ASSIGNED` | 管理员 |
| `ASSIGN` | `ASSIGNED` | `ASSIGNED` | 管理员（改派） |
| `ASSIGN` | `IN_PROGRESS` | `ASSIGNED` | 管理员（改派） |
| `ACCEPT` | `ASSIGNED` | `IN_PROGRESS` | 该工单处理人 |
| `RESOLVE` | `IN_PROGRESS` | `RESOLVED` | 该工单处理人 |
| `CONFIRM` | `RESOLVED` | `CLOSED` | 提交人 |
| `REJECT` | `RESOLVED` | `IN_PROGRESS` | 提交人 |
| `REOPEN` | `CLOSED` | `IN_PROGRESS` | 提交人 |
| `CANCEL` | `NEW` | `CANCELLED` | 提交人 |
| `CANCEL` | `ASSIGNED` | `CANCELLED` | 提交人 |

### 3.2 实现

```java
public enum TicketStatus { NEW, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED }

public enum TicketAction { ASSIGN, ACCEPT, RESOLVE, CONFIRM, REJECT, REOPEN, CANCEL }

public enum ActorRule { ADMIN, ASSIGNEE, REPORTER }
```

`TicketStateMachine` 内部维护 `Map<Key, Transition>`，只暴露两个方法：

```java
Transition require(TicketStatus from, TicketAction action);  // 非法则抛 BusinessException
boolean isAllowed(TicketStatus from, TicketAction action);   // 给页面决定显示哪些按钮
```

`TicketService` 每个动作都是同样的五步：

1. `ticketMapper.findById(orgId, id)`（不存在 → `TICKET_NOT_FOUND`）
2. 校验数据归属（不是你的 → `FORBIDDEN`）
3. `stateMachine.require(当前状态, 动作)`
4. 校验角色规则（`ADMIN` / `ASSIGNEE` / `REPORTER`）
5. `updateStatus` + 写一条 `ticket_event`

页面按钮由 `isAllowed` 生成，和后端校验共用同一份规则。

### 3.3 事件写法

| 动作 | `action` | `detail` |
| --- | --- | --- |
| 分派 | `ASSIGNED` | `{"from":3,"to":5}` |
| 接单 | `ACCEPTED` | `{}` |
| 解决 | `RESOLVED` | `{"note":"..."}` |
| 确认 | `CONFIRMED` | `{}` |
| 退回 | `REJECTED` | `{"reason":"..."}` |
| 重开 | `REOPENED` | `{}` |
| 撤销 | `CANCELLED` | `{}` |
| 回复 | `REPLIED` | `{"replyId":12,"visibility":"PUBLIC"}` |

---

## 4. 认证与授权

### 4.1 认证：Session（相对需求文档的改动）

需求文档写的是“登录返回访问令牌”，这里改为 **Session + 表单登录**：

- 页面是服务端渲染，Session 天然适配；
- Spring Security 自带 CSRF、会话固定攻击防护、登出失效；
- JWT 在服务端渲染场景要自己解决令牌存放、续期、撤销，是纯负担。

将来开放 API 时再加 Bearer Token，按路径前缀区分，两者可共存。

### 4.2 授权：两层

**第一层，角色**：`hasRole` 限制 URL 前缀（`/admin/**` → `ADMIN`）。

**第二层，资源归属**：Service 里判断“这条工单是不是你的”，因为角色表达不了“你是 AGENT 但这条不是分给你的”。

| 场景 | 结果 |
| --- | --- |
| 未登录访问业务页 | 302 → `/login` |
| 角色不足 | 403 |
| 资源不属于你 | 403，响应体与“不存在”一致 |

> 统一 403 而不是 404，是为了不让用户靠错误码探测“某条工单是否存在”。

### 4.3 密码

BCrypt（`BCryptPasswordEncoder`，强度 10），库里只存哈希，日志里永不打印密码。

---

## 5. 页面与路由

全部“表单 + 重定向”（POST-Redirect-GET），MVP 不提供 JSON API。

| 方法 | 路径 | 作用 | 权限 |
| --- | --- | --- | --- |
| GET | `/` | 跳转 `/tickets` | 登录 |
| GET | `/login` | 登录页 | 公开 |
| POST | `/login` | 提交登录 | 公开 |
| POST | `/logout` | 登出 | 登录 |
| GET | `/register` | 注册页 | 公开 |
| POST | `/register` | 提交注册 | 公开 |
| GET | `/tickets` | 列表（筛选、搜索、分页） | 登录 |
| GET | `/tickets/new` | 新建页 | 登录 |
| POST | `/tickets` | 创建 | 登录 |
| GET | `/tickets/{id}` | 详情 + 回复流 + 时间线 | 有权限者 |
| GET | `/tickets/{id}/edit` | 编辑页（仅 `NEW`） | 提交人 / 管理员 |
| POST | `/tickets/{id}` | 保存编辑 | 提交人 / 管理员 |
| POST | `/tickets/{id}/assign` | 分派 / 改派 | 管理员 |
| POST | `/tickets/{id}/accept` | 接单 | 处理人 |
| POST | `/tickets/{id}/resolve` | 标记解决 | 处理人 |
| POST | `/tickets/{id}/confirm` | 确认关闭 | 提交人 |
| POST | `/tickets/{id}/reject` | 退回 | 提交人 |
| POST | `/tickets/{id}/reopen` | 重开 | 提交人 |
| POST | `/tickets/{id}/cancel` | 撤销 | 提交人 |
| POST | `/tickets/{id}/replies` | 发表回复 / 内部备注 | 见权限矩阵 |
| GET | `/admin/users` | 用户与角色管理 | 管理员 |
| POST | `/admin/users/{id}` | 保存角色 / 启用状态 | 管理员 |

**为什么用动词路径而不是 `PATCH` 改 `status` 字段**：每个动作的权限、必填参数、副作用都不同（解决要填说明，退回要填原因）。拆成独立端点，校验和授权各归各位，事件记录也天然一一对应。

### 5.1 页面清单

| 模板 | 内容 |
| --- | --- |
| `layout.html` | 骨架：导航（当前用户、角色）、消息提示 |
| `login.html` / `register.html` | 表单 + 错误提示 |
| `tickets/list.html` | 筛选栏、列表、分页 |
| `tickets/new.html` / `tickets/edit.html` | 工单表单 |
| `tickets/detail.html` | 字段、状态、动作按钮、回复流、时间线 |
| `admin/users.html` | 用户列表、角色下拉、启用开关 |

---

## 6. Mapper 设计

### 6.1 接口与 SQL 的对应

```java
@Mapper
public interface TicketMapper {
    Ticket findById(@Param("orgId") long orgId, @Param("id") long id);

    List<Ticket> search(@Param("orgId") long orgId,
                        @Param("f") TicketFilter filter,
                        @Param("offset") int offset,
                        @Param("size") int size);

    long count(@Param("orgId") long orgId, @Param("f") TicketFilter filter);

    int insert(Ticket ticket);            // useGeneratedKeys 回填 id

    int updateEditable(Ticket ticket);    // 仅 NEW 状态可改

    int updateStatus(@Param("id") long id,
                     @Param("version") int version,
                     @Param("status") TicketStatus status,
                     @Param("assigneeId") Long assigneeId,
                     @Param("resolvedAt") LocalDateTime resolvedAt,
                     @Param("closedAt") LocalDateTime closedAt);
}
```

### 6.2 并发控制：手写版本号

没有 JPA 的 `@Version` 了，改成在 SQL 里做条件更新：

```xml
<update id="updateStatus">
    update ticket
    set status      = #{status},
        assignee_id = #{assigneeId},
        resolved_at = #{resolvedAt},
        closed_at   = #{closedAt},
        updated_at  = current_timestamp(6),
        version     = version + 1
    where id = #{id}
      and version = #{version}
</update>
```

Service 里检查返回值：

```java
if (ticketMapper.updateStatus(...) == 0) {
    throw new BusinessException(ErrorCode.TICKET_CONCURRENT_MODIFIED);
}
```

影响行数为 0，说明这条记录在读取之后被别人改过（版本号对不上）。这比 JPA 的悲观/乐观锁更直白，也更容易讲清楚原理。

### 6.3 按天取号

```sql
insert into ticket_day_seq (seq_date, last_no)
values (current_date, 1)
on duplicate key update last_no = last_no + 1;

select last_no from ticket_day_seq where seq_date = current_date;
```

`insert ... on duplicate key update` 会锁住这一行，同一事务内的 `select` 读到的就是自己刚更新的值；并发请求会在这里排队，不会撞号。

### 6.4 分页

不用 PageHelper，手写两条 SQL：

```sql
select ... from ticket where ... order by updated_at desc limit #{size} offset #{offset};
select count(*) from ticket where ...;
```

多写几行，但分页原理（总数、偏移、边界）能真正搞明白。

### 6.5 动态筛选

```xml
<if test="f.status != null"> and status = #{f.status} </if>
<if test="f.categoryId != null"> and category_id = #{f.categoryId} </if>
<if test="f.q != null and f.q != ''"> and title like concat('%', #{f.q}, '%') </if>
```

**只用 `#{}`，永远不用 `${}`**：`#{}` 是预编译参数，`${}` 是字符串拼接，会引入 SQL 注入。这条没有例外。

---

## 7. 错误码

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `AUTH_BAD_CREDENTIALS` | 401 | 用户名或密码错误（不区分哪个错） |
| `AUTH_REQUIRED` | 401 | 未登录 |
| `FORBIDDEN` | 403 | 无权限 |
| `USERNAME_TAKEN` | 409 | 用户名已存在 |
| `TICKET_NOT_FOUND` | 404 | 工单不存在 |
| `TICKET_ILLEGAL_TRANSITION` | 409 | 当前状态不允许该操作 |
| `TICKET_CONCURRENT_MODIFIED` | 409 | 并发修改冲突 |
| `VALIDATION_FAILED` | 400 | 参数校验失败 |
| `INTERNAL_ERROR` | 500 | 未预期错误 |

页面场景渲染 `error.html`，显示“发生了什么 + 你能做什么”，不显示堆栈。

---

## 8. 测试策略

| 层 | 工具 | 测什么 |
| --- | --- | --- |
| 状态机 | JUnit 5 纯单元测试 | 遍历 `状态 × 动作` 全部组合，断言合法集合与转移表**完全相等** |
| Service | `@SpringBootTest` + Testcontainers（MySQL 8.4） | 权限归属、状态流转、事件落库、版本冲突 |
| Web | `@SpringBootTest` + `MockMvc` | 未登录跳转、角色拦截、CSRF、校验错误回显 |
| 端到端 | 一个完整链路测试 | 创建 → 分派 → 接单 → 解决 → 确认，断言事件表正好 5 条 |

状态机测试刻意“遍历全组合”而不是列合法用例：将来新增一个状态，没覆盖的组合会自动失败。

---

## 9. 部署与配置

```
compose（生产）
├── app   Spring Boot 镜像（多阶段构建）
└── db    mysql:8.4，数据卷持久化
```

- 配置全部来自环境变量：`DB_URL`、`DB_USER`、`DB_PASSWORD`。
- `/actuator/health` 作为健康检查。
- 日志到 stdout，每行带 `traceId`。
- 表结构由应用启动时自动执行 Flyway 迁移，不手工执行 SQL。
- 生产环境关闭开发用的 docker-compose 支持：`SPRING_DOCKER_COMPOSE_ENABLED=false`。

---

## 10. 编码约定

| 项 | 约定 |
| --- | --- |
| 命名 | 表/列 `snake_case`，类 `PascalCase` |
| SQL | 只在 Mapper XML；只用 `#{}`；关键字小写，便于阅读 |
| 时间 | 存 UTC `LocalDateTime`，展示前格式化 |
| 校验 | 表单用 Jakarta Validation；业务规则在 Service |
| 异常 | 业务失败抛 `BusinessException(错误码)`，不用 null 表示失败 |
| 日志 | 记录动作与工单号，不记录密码、令牌、完整请求体 |
| 提交 | 一个提交只做一件事，信息写“为什么” |

---

## 11. 待确认

1. 认证从 JWT 改为 Session（4.1 节），确认后同步更新需求文档 NFR-03。
2. MySQL 版本定为 8.4 LTS（不是 9.x innovation），理由是长期支持、生态兼容。
