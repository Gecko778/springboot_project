package com.example.ticket.auth;

import com.example.ticket.support.AbstractIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证与授权测试（M05、M06）。
 * 拷到：src/test/java/com/example/ticket/auth/TicketAccessIT.java
 */
class TicketAccessIT extends AbstractIT {

    @Test
    @DisplayName("T05-1 未登录访问列表，应跳转登录页")
    void 未登录跳转() throws Exception {
        mvc.perform(get("/tickets"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("T05-2 注册后能登录，且能看到列表")
    void 注册后可登录() throws Exception {
        String user = newUser();
        MockHttpSession session = login(user);

        mvc.perform(get("/tickets").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("T05-3 密码错误，跳回登录页且不暴露用户是否存在")
    void 密码错误() throws Exception {
        String user = newUser();

        mvc.perform(formLogin("/login").user(user).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrlPattern("**/login*"));
    }

    @Test
    @DisplayName("T05-5 重复用户名注册，不产生第二个用户")
    void 重复用户名() throws Exception {
        String user = newUser();
        register(user, "password123", "重复");

        mvc.perform(post("/register").with(csrf())
                        .param("username", user)
                        .param("displayName", "重复2")
                        .param("password", "password123")
                        .param("confirmPassword", "password123"))
                .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "select count(*) from app_user where username = ?", Integer.class, user);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("T06-1 提交人只能看到自己的工单")
    void 提交人只看自己() throws Exception {
        String alice = newUser();
        String bob = newUser();
        MockHttpSession aliceSession = login(alice);
        MockHttpSession bobSession = login(bob);

        createTicket(aliceSession, "[IT] alice 的工单");
        createTicket(bobSession, "[IT] bob 的工单");

        mvc.perform(get("/tickets").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice 的工单")))
                .andExpect(content().string(not(containsString("bob 的工单"))));
    }

    @Test
    @DisplayName("T06-2 处理人只能看到分派给自己的工单")
    void 处理人只看分派() throws Exception {
        String reporter = newUser();
        String agent = newUserWithRole("AGENT");
        String admin = newUserWithRole("ADMIN");

        long ticketId = createTicket(login(reporter), "[IT] 需要分派");
        long agentId = userId(agent);

        act(login(admin), ticketId, "assign", "assigneeId", String.valueOf(agentId));

        mvc.perform(get("/tickets").session(login(agent)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("需要分派")));
    }

    @Test
    @DisplayName("T06-4 提交人访问他人工单详情，应 403")
    void 越权看详情() throws Exception {
        String alice = newUser();
        String bob = newUser();
        long ticketId = createTicket(login(alice), "[IT] 私有工单");

        mvc.perform(get("/tickets/" + ticketId).session(login(bob)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("T06-5 提交人调用分派接口，应 403 且不改变数据")
    void 越权分派() throws Exception {
        String reporter = newUser();
        String agent = newUserWithRole("AGENT");
        long ticketId = createTicket(login(reporter), "[IT] 不该被分派");

        MvcResult result = act(login(reporter), ticketId, "assign",
                "assigneeId", String.valueOf(userId(agent)));

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("NEW", statusOf(ticketId));
        assertEquals(0, countEvents(ticketId));
    }

    private long userId(String username) {
        Long id = jdbc.queryForObject("select id from app_user where username = ?", Long.class, username);
        return id == null ? -1 : id;
    }
}
