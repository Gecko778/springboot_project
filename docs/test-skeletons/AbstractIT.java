package com.example.ticket.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：真实 MySQL（Testcontainers）+ 真实登录。
 *
 * 拷到：src/test/java/com/example/ticket/support/AbstractIT.java
 *
 * 需要 pom 里有两个 test 依赖（Initializr 默认没有）：
 *   org.springframework.security:spring-security-test
 *   org.testcontainers:mysql
 *
 * 约定：
 *   - 用户名统一前缀 it_，每次自增，避免用例间互相干扰
 *   - 每个用例前清空工单相关表（保留用户与分类）
 *   - 需要 Docker 正在运行
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ticket")
            .withUsername("ticket")
            .withPassword("ticket")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("characterEncoding", "UTF-8")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_reply");
        jdbc.update("delete from ticket");
    }

    // ---------- 用户 ----------

    /** 注册一个用户，返回用户名。 */
    protected String newUser() throws Exception {
        String username = "it_" + SEQ.incrementAndGet();
        register(username, "password123", username.toUpperCase());
        return username;
    }

    protected void register(String username, String password, String displayName) throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("username", username)
                        .param("displayName", displayName)
                        .param("password", password)
                        .param("confirmPassword", password))
                .andExpect(status().is3xxRedirection());
    }

    /** 直接把角色写进数据库（注册默认是 REPORTER）。 */
    protected void promote(String username, String role) {
        jdbc.update("update app_user set role = ? where username = ?", role, username);
    }

    /** 新建一个指定角色的用户。 */
    protected String newUserWithRole(String role) throws Exception {
        String username = newUser();
        promote(username, role);
        return username;
    }

    // ---------- 登录 ----------

    /** 通过真实表单登录，返回会话。 */
    protected MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(username).password(password))
                .andExpect(authenticated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected MockHttpSession login(String username) throws Exception {
        return login(username, "password123");
    }

    // ---------- 工单 ----------

    /** 以指定会话创建工单，返回工单 id（从 302 的 Location 里解析）。 */
    protected long createTicket(MockHttpSession session, String title) throws Exception {
        MvcResult result = mvc.perform(post("/tickets").session(session).with(csrf())
                        .param("title", title)
                        .param("description", "这是集成测试创建的工单描述，长度足够通过校验。")
                        .param("categoryId", "1")
                        .param("priority", "NORMAL"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return idFromLocation(result);
    }

    protected long idFromLocation(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        if (location == null) {
            throw new AssertionError("期望重定向，但没有 Location 头");
        }
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    /** 以会话执行一个动作端点。 */
    protected MvcResult act(MockHttpSession session, long ticketId, String action, String... params)
            throws Exception {
        var request = post("/tickets/" + ticketId + "/" + action).session(session).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    protected String statusOf(long ticketId) {
        return jdbc.queryForObject("select status from ticket where id = ?", String.class, ticketId);
    }

    protected int countEvents(long ticketId) {
        Integer n = jdbc.queryForObject("select count(*) from ticket_event where ticket_id = ?",
                Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    /** 未登录时访问某路径，断言被重定向到登录页。 */
    protected void assertRedirectsToLogin(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is3xxRedirection());
    }
}
