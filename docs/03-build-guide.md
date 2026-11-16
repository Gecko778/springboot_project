# 工单系统 执行手册（M1–M4）

技术栈：Java 21 + Spring Boot 4.1 + MyBatis + MySQL 8.4 + Thymeleaf。
配套文档：`01-requirements.md`（做什么）、`02-design.md`（怎么设计）。

**用法**：一次只做一步。做完用该步的“验收”确认，打勾，再往下。

> **按 M01 执行时**：`04-progress.md` 把 P1 拆成了 M01–M04。M01 只做 §1.1 的工程生成（依赖里去掉 `flyway`、`mysql`、`testcontainers`），应用配置按教程 §4；§1.2–§1.4 属于 M02。详见 [M01-框架搭建教程.md](M01-框架搭建教程.md)。

---

## 0. 环境准备

当前机器：Java 21 ✅、Git ✅、Maven ❌、Docker ❌。Maven 不用装（用项目自带的 `mvnw`），Docker 装一次。

### 0.1 安装 Docker

```bash
brew install --cask docker
open -a Docker        # 等菜单栏图标变绿
docker run --rm hello-world
```

看到 `Hello from Docker!` 即成功。嫌重可换 OrbStack：`brew install --cask orbstack`。

### 0.2 确认目录

```bash
cd /Users/gecko/programs/spring_boot_project
ls          # 只有 docs/
```

---

## 1. M1 骨架与数据层

**目标**：应用能启动，Flyway 建好 7 张表，浏览器能看到从 MySQL 读出的工单列表。

### 1.1 生成项目

```bash
cd /Users/gecko/programs/spring_boot_project
curl -G https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d javaVersion=21 \
  -d groupId=com.example \
  -d artifactId=ticket \
  -d name=ticket \
  -d packageName=com.example.ticket \
  -d packaging=jar \
  -d dependencies=web,thymeleaf,validation,security,flyway,actuator,devtools,testcontainers,mysql \
  -o ticket.zip
unzip -q ticket.zip -d .
rm ticket.zip
ls
```

应看到 `pom.xml`、`mvnw`、`src/`。

> 依赖 id 若报错，打开 <https://start.spring.io> 按同名勾选再下载。**不要**勾 JPA，也不要用 docker-compose 支持（本项目自己写 compose 文件，见 1.2）。

### 1.2 手动补两个依赖 + 写 compose

**a. MyBatis 和 Flyway 的 MySQL 模块，Initializr 没有，必须自己加。**

在 `pom.xml` 的 `<dependencies>` 里加：

```xml
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>4.1.0</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

版本对应关系：`mybatis-spring-boot-starter 4.1.x` ↔ Spring Boot 4.1.x。如果你的 Boot 是 4.0.x，把 starter 换成 `4.0.1`。

**b. 新建 `compose.yaml`（开发用数据库）：**

```yaml
services:
  db:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ticket
      MYSQL_USER: ticket
      MYSQL_PASSWORD: ticket
    ports:
      - "3306:3306"
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci
    volumes:
      - mysqldata:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uticket", "-pticket"]
      interval: 5s
      timeout: 3s
      retries: 20
volumes:
  mysqldata:
```

```bash
docker compose up -d
docker compose ps          # 等 STATUS 变成 healthy，首次约 20–30 秒
```

### 1.3 配置

`src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticket?connectionTimeZone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false
    username: ticket
    password: ticket
  flyway:
    enabled: true

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

几个参数的意思：

- `connectionTimeZone=UTC`：数据库里存的时间按 UTC 理解，避免服务器时区不同导致差 8 小时；
- `allowPublicKeyRetrieval=true`：MySQL 8 默认的密码插件在非 SSL 连接下需要这个，不加会报 `Public Key Retrieval is not allowed`；
- `map-underscore-to-camel-case=true`：`created_at` 自动映射到 `createdAt`，省掉大量 `resultMap`。

### 1.4 建表

新建 `src/main/resources/db/migration/V1__init.sql`，内容**原样复制** `02-design.md` 第 2.2 节。

```bash
./mvnw spring-boot:run
```

启动后 Flyway 会自动执行迁移。验证：

```bash
docker compose exec db mysql -uticket -pticket ticket -e "show tables;"
```

应看到 7 张表。再确认结构：

```bash
docker compose exec db mysql -uticket -pticket ticket -e "describe ticket;"
```

### 1.5 实体与 Mapper

**阅读**：<https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/index.html>（只看“起步依赖”和“配置”两节）

先写 `Ticket`、`User`、`Category`、`TicketReply`、`TicketEvent` 五个普通 Java 类（无参构造 + getter/setter，IDE 生成即可），字段名与表列一一对应（camelCase）。

每个 Mapper 接口上标 `@Mapper`：

```java
@Mapper
public interface TicketMapper {
    List<Ticket> findAll(@Param("orgId") long orgId);
}
```

对应 `src/main/resources/mapper/TicketMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.ticket.ticket.mapper.TicketMapper">
    <select id="findAll" resultType="com.example.ticket.ticket.Ticket">
        select * from ticket where org_id = #{orgId} order by updated_at desc
    </select>
</mapper>
```

三条铁律：

1. `namespace` 必须等于接口的全限定名；
2. `<select id>` 必须等于方法名；
3. 接口方法有多个参数时，每个都要 `@Param`，否则报 `Parameter 'xxx' not found`。

### 1.6 种子数据

新建 `V2__seed.sql`：

```sql
insert into org (id, name) values (1, '示例公司');
insert into category (org_id, name, sort_order) values
  (1, '硬件故障', 10),
  (1, '软件问题', 20),
  (1, '账号权限', 30),
  (1, '其他', 99);
```

管理员账号不要写死在 SQL 里（密码哈希不好手写）。写一个 `DataInitializer implements ApplicationRunner`：启动时若 `admin` 不存在，用 `PasswordEncoder` 编码 `admin12345` 后插入。

### 1.7 第一个页面

**阅读**：<https://spring.io/guides/gs/serving-web-content/>

`TicketController` 加 `GET /tickets`：调 `ticketMapper.findAll(1)`，放进 `Model`，渲染 `templates/tickets/list.html`。

先插两条假数据：

```sql
insert into ticket (org_id, code, title, description, category_id, priority, status, reporter_id)
values (1, 'TK-20260101-0001', '打印机卡纸', '三楼打印机一直卡纸', 1, 'HIGH', 'NEW', 1);
```

**验收**：<http://localhost:8080/tickets> 能看到工单标题。

### 1.8 提交

```bash
git init
printf 'target/\n.env\n*.log\n.idea/\n' > .gitignore
git add -A
git commit -m "chore: 项目骨架、Flyway 迁移与工单列表"
```

---

## 2. M2 登录与权限

**目标**：未登录看不到业务页；三种角色范围不同；越权被拦。

### 2.1 表单登录

**阅读**：
- <https://spring.io/guides/gs/securing-web/>
- <https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html>

`auth/SecurityConfig`：放行 `/login`、`/register`、`/css/**`，其余要求登录；成功跳 `/tickets`；登出跳 `/login`。

> Spring Security 6 起取消 `.and()`，必须用 Lambda DSL。2023 年前的教程大多编译不过。

### 2.2 用户来自数据库

写 `DbUserDetailsService implements UserDetailsService`：`UserMapper.findByUsername` 查用户，把 `role` 映射成 `ROLE_REPORTER` / `ROLE_AGENT` / `ROLE_ADMIN`；`enabled = 0` 拒绝登录。

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

### 2.3 注册页

**阅读**：<https://spring.io/guides/gs/validating-form-input/>

`RegisterController` + `register.html`：用户名、显示名、密码、确认密码，规则见需求 FR-01，重名返回 `USERNAME_TAKEN`。

> Thymeleaf 表单必须写 `th:action="@{/register}"`，裸 `action` 拿不到 CSRF token，提交会 403。

### 2.4 数据范围

列表 SQL 加范围条件（M2 先写在 Controller，M3 抽到 Service）：

| 角色 | 条件 |
| --- | --- |
| `REPORTER` | `reporter_id = 当前用户` |
| `AGENT` | `assignee_id = 当前用户` |
| `ADMIN` | 无额外条件 |

造测试用户：

```sql
update app_user set role = 'AGENT' where username = 'agent1';
update app_user set role = 'ADMIN' where username = 'admin';
```

**验收**：
- 未登录访问 `/tickets` → 跳登录页
- `REPORTER` 看不到别人的工单
- 访问不属于自己的 `/tickets/1` → 403

### 2.5 管理员页（最小版）

`GET /admin/users` 列表 + 角色下拉 + 启用开关，`POST /admin/users/{id}` 保存。只做这一页。

**提交**：`feat: 表单登录、角色与数据范围`

---

## 3. M3 业务闭环

**目标**：场景 A–D 走通，非法跳转返回 409。

### 3.1 先写状态机测试

**阅读**：`02-design.md` 第 3 节。

先写 `TicketStateMachineTest`：`@ParameterizedTest` + `@EnumSource` 遍历 `TicketStatus × TicketAction` 共 42 种组合，断言合法性判断与设计表完全一致；再写 `TicketStateMachine` 让它变绿。

### 3.2 动作端点

按 `02-design.md` 3.2 的“五步法”实现 7 个动作。

每个动作都要：查工单 → 校验归属 → 状态机校验 → 角色校验 → 改状态 + 写事件。

**验收**：对 `CLOSED` 的工单调 `accept`，必须 409 且状态不变。

### 3.3 回复与内部备注

`POST /tickets/{id}/replies`，正文必填，`visibility` 由后端按角色决定（`AGENT`/`ADMIN` 才能传 `INTERNAL`）。

**关键**：内部备注的过滤放在 SQL 里。`REPORTER` 查询回复流时 SQL 就带 `and visibility = 'PUBLIC'`，不能查出来再在模板里隐藏。

### 3.4 详情页

字段、状态、由 `isAllowed` 生成的动作按钮、回复流、事件时间线。

### 3.5 并发冲突

`updateStatus` 返回 0 时抛 `TICKET_CONCURRENT_MODIFIED`，页面提示“这条工单刚被别人改过，请刷新后重试”。

**验收**：两个窗口同时对同一工单点“接单”，一个成功，另一个看到冲突提示。

### 3.6 走场景

按需求文档 3.1 节手工走 A、B、C、D 四遍。

**提交**：`feat: 状态机、流转动作与回复`

---

## 4. M4 质量与交付

### 4.1 集成测试

**阅读**：
- <https://java.testcontainers.org/modules/databases/mysql/>
- <https://docs.spring.io/spring-boot/reference/testing/testcontainers.html>

在 `pom.xml` 加：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

写 `@SpringBootTest` + `@Testcontainers`：起 MySQL 8.4 容器，跑完整链路（创建 → 分派 → 接单 → 解决 → 确认），断言 `ticket_event` 正好 5 条。

> Testcontainers 需要 Docker 在运行，否则会卡住而不是报错。

### 4.2 CI

`.github/workflows/ci.yml`：

```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: ./mvnw -B verify
```

推到 GitHub，确认 Actions 变绿。

### 4.3 镜像

`Dockerfile`：

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src ./src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### 4.4 部署

`deploy/compose.yaml`（生产用，和开发用的根目录 `compose.yaml` 分开）：

```yaml
services:
  db:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:?set DB_ROOT_PASSWORD}
      MYSQL_DATABASE: ticket
      MYSQL_USER: ticket
      MYSQL_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD}
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci
    volumes:
      - mysqldata:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uticket", "-p${DB_PASSWORD}"]
      interval: 5s
      retries: 20
  app:
    build: ..
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://db:3306/ticket?connectionTimeZone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false
      SPRING_DATASOURCE_USERNAME: ticket
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    ports:
      - "8080:8080"
    depends_on:
      db:
        condition: service_healthy
volumes:
  mysqldata:
```

```bash
cd deploy
DB_PASSWORD='换成强密码' DB_ROOT_PASSWORD='再一个强密码' docker compose up -d --build
curl http://localhost:8080/actuator/health
```

要求 `{"status":"UP"}`，然后 `docker compose restart app` 确认数据还在。

### 4.5 收尾

`README.md`：项目是什么、怎么启动、三个演示账号、截图、已知限制。

**提交**：`chore: 测试、CI、镜像与部署`

---

## 5. 自检命令

```bash
docker compose up -d                    # 开发数据库
./mvnw -q test                          # 单元 + 集成测试
./mvnw -q -DskipTests package           # 打包
./mvnw spring-boot:run                  # 本地运行
curl -s localhost:8080/actuator/health  # 健康检查
```

---

## 6. 常见坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动报 `Unsupported Database: MySQL` | 缺 `flyway-mysql` | 见 1.2a |
| `Public Key Retrieval is not allowed` | 连接串缺参数 | 加 `allowPublicKeyRetrieval=true&useSSL=false` |
| `Invalid bound statement (not found)` | XML 的 namespace/id 与接口对不上，或 XML 没被扫描 | 检查 `mapper-locations` 与文件名 |
| `Parameter 'xxx' not found` | 多参数没加 `@Param` | 每个参数都标 |
| 查出来字段是 null | 列名没映射 | 开 `map-underscore-to-camel-case` |
| 插入后 `id` 为 null | 缺 `useGeneratedKeys` | `<insert useGeneratedKeys="true" keyProperty="id">` |
| 时间差 8 小时 | 时区没统一 | 连接串 `connectionTimeZone=UTC`，展示时再转 |
| 中文问号 | 字符集不对 | 库 `utf8mb4` + 连接串 `characterEncoding=UTF-8` |
| `FlywayValidateException` | 改了已执行过的迁移文件 | 新建 `V3__xxx.sql`，不要回头改 |
| 表单提交 403 | 缺 CSRF token | 表单用 `th:action` |
| `.and()` 编译不过 | 抄了旧版 Security 教程 | 改 Lambda DSL |
| Testcontainers 卡住 | Docker 没启动 | 先 `open -a Docker` |
| 3306 端口被占 | 本机已有 MySQL | 改 compose 端口，同步改连接串 |

---

## 7. 学习资料

| 主题 | 链接 |
| --- | --- |
| Spring Boot 入门 | <https://spring.io/guides/gs/spring-boot/> |
| 页面与 Thymeleaf | <https://spring.io/guides/gs/serving-web-content/> |
| 表单校验 | <https://spring.io/guides/gs/validating-form-input/> |
| 安全入门 | <https://spring.io/guides/gs/securing-web/> |
| Security 参考手册 | <https://docs.spring.io/spring-security/reference/> |
| Spring Boot 参考文档 | <https://docs.spring.io/spring-boot/> |
| MyBatis 官方文档 | <https://mybatis.org/mybatis-3/zh_CN/index.html> |
| MyBatis Spring Boot 集成 | <https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/index.html> |
| Flyway 文档 | <https://documentation.red-gate.com/flyway> |
| MySQL 8.4 参考手册 | <https://dev.mysql.com/doc/refman/8.4/en/> |
| Testcontainers | <https://java.testcontainers.org/> |

> 版本判断：文章用到 `.and()`、`WebSecurityConfigurerAdapter`、`javax.*` 包名的，都是老版本写法，只参考思路，别照抄。
