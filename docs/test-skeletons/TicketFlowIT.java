package com.example.ticket.ticket;

import com.example.ticket.support.AbstractIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 工单完整链路测试（M07、M08、M09）。
 * 拷到：src/test/java/com/example/ticket/ticket/TicketFlowIT.java
 *
 * 这些断言就是需求文档里的场景 A。
 */
class TicketFlowIT extends AbstractIT {

    @Test
    @DisplayName("T07/T08 提交→分派→接单→解决→确认，最终关闭且事件数=5")
    void 完整链路() throws Exception {
        String reporter = newUser();
        String agent = newUserWithRole("AGENT");
        String admin = newUserWithRole("ADMIN");
        MockHttpSession reporterSession = login(reporter);
        MockHttpSession agentSession = login(agent);
        MockHttpSession adminSession = login(admin);

        long id = createTicket(reporterSession, "[IT] 打印机卡纸");
        assertEquals("NEW", statusOf(id));

        String code = jdbc.queryForObject("select code from ticket where id = ?", String.class, id);
        assertTrue(code.matches("TK-\\d{8}-\\d{4}"), "编号格式错误：" + code);

        assertEquals(302, act(adminSession, id, "assign",
                "assigneeId", String.valueOf(userId(agent))).getResponse().getStatus());
        assertEquals("ASSIGNED", statusOf(id));

        assertEquals(302, act(agentSession, id, "accept").getResponse().getStatus());
        assertEquals("IN_PROGRESS", statusOf(id));

        assertEquals(302, act(agentSession, id, "resolve", "note", "换了一包纸，已恢复正常").getResponse().getStatus());
        assertEquals("RESOLVED", statusOf(id));

        assertEquals(302, act(reporterSession, id, "confirm").getResponse().getStatus());
        assertEquals("CLOSED", statusOf(id));

        assertEquals(5, countEvents(id), "5 个动作应产生 5 条事件");
    }

    @Test
    @DisplayName("T08-4 对已关闭工单执行接单，应 409 且状态不变")
    void 非法跳转() throws Exception {
        String reporter = newUser();
        String agent = newUserWithRole("AGENT");
        String admin = newUserWithRole("ADMIN");
        MockHttpSession reporterSession = login(reporter);

        long id = createTicket(reporterSession, "[IT] 已完成的工单");
        act(login(admin), id, "assign", "assigneeId", String.valueOf(userId(agent)));
        act(login(agent), id, "accept");
        act(login(agent), id, "resolve", "note", "已处理完成");
        act(reporterSession, id, "confirm");
        assertEquals("CLOSED", statusOf(id));

        MvcResult result = act(login(agent), id, "accept");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CLOSED", statusOf(id), "非法操作不得改变状态");
    }

    @Test
    @DisplayName("T09-2 内部备注对提交人不可见")
    void 内部备注不可见() throws Exception {
        String reporter = newUser();
        String agent = newUserWithRole("AGENT");
        String admin = newUserWithRole("ADMIN");
        MockHttpSession reporterSession = login(reporter);
        MockHttpSession agentSession = login(agent);

        long id = createTicket(reporterSession, "[IT] 带内部备注的工单");
        act(login(admin), id, "assign", "assigneeId", String.valueOf(userId(agent)));
        act(agentSession, id, "accept");

        String secret = "内部备注标记-XYZZY";
        mvc.perform(post("/tickets/" + id + "/replies").session(agentSession).with(csrf())
                        .param("body", secret)
                        .param("visibility", "INTERNAL"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/tickets/" + id).session(reporterSession))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("XYZZY"))));

        mvc.perform(get("/tickets/" + id).session(agentSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("XYZZY")));
    }

    @Test
    @DisplayName("T07-4 标题过短，应回显错误且不落库")
    void 校验失败不落库() throws Exception {
        MockHttpSession session = login(newUser());

        mvc.perform(post("/tickets").session(session).with(csrf())
                        .param("title", "abc")
                        .param("description", "描述长度是够的，但是标题只有三个字符。")
                        .param("categoryId", "1")
                        .param("priority", "NORMAL"))
                .andExpect(status().isOk());

        Integer count = jdbc.queryForObject("select count(*) from ticket", Integer.class);
        assertEquals(0, count);
    }

    private long userId(String username) {
        Long id = jdbc.queryForObject("select id from app_user where username = ?", Long.class, username);
        return id == null ? -1 : id;
    }
}
