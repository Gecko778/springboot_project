package com.example.ticket.ticket.domain;

import com.example.ticket.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机测试（L1 单元测试，不启动 Spring）。
 *
 * 拷到：src/test/java/com/example/ticket/ticket/domain/TicketStateMachineTest.java
 *
 * 设计意图：不写“合法用例列表”，而是遍历 6 状态 × 7 动作 = 42 种组合，
 * 断言“合法与否”与下面的 LEGAL 表完全一致。
 * 将来新增一个状态时，没覆盖到的组合会自动失败。
 *
 * 如果你的 Transition 字段名不是 to()，只改 assertLegal 里那两行即可。
 */
class TicketStateMachineTest {

    private final TicketStateMachine machine = new TicketStateMachine();

    /** 唯一事实：合法转移表。key = 状态:动作，value = 目标状态。 */
    private static final Map<String, TicketStatus> LEGAL = new HashMap<>();

    static {
        put(TicketStatus.NEW, TicketAction.ASSIGN, TicketStatus.ASSIGNED);
        put(TicketStatus.ASSIGNED, TicketAction.ASSIGN, TicketStatus.ASSIGNED);
        put(TicketStatus.IN_PROGRESS, TicketAction.ASSIGN, TicketStatus.ASSIGNED);
        put(TicketStatus.ASSIGNED, TicketAction.ACCEPT, TicketStatus.IN_PROGRESS);
        put(TicketStatus.IN_PROGRESS, TicketAction.RESOLVE, TicketStatus.RESOLVED);
        put(TicketStatus.RESOLVED, TicketAction.CONFIRM, TicketStatus.CLOSED);
        put(TicketStatus.RESOLVED, TicketAction.REJECT, TicketStatus.IN_PROGRESS);
        put(TicketStatus.CLOSED, TicketAction.REOPEN, TicketStatus.IN_PROGRESS);
        put(TicketStatus.NEW, TicketAction.CANCEL, TicketStatus.CANCELLED);
        put(TicketStatus.ASSIGNED, TicketAction.CANCEL, TicketStatus.CANCELLED);
    }

    private static void put(TicketStatus from, TicketAction action, TicketStatus to) {
        LEGAL.put(from + ":" + action, to);
    }

    static Stream<Arguments> allCombinations() {
        return Stream.of(TicketStatus.values())
                .flatMap(from -> Stream.of(TicketAction.values())
                        .map(action -> Arguments.of(from, action, LEGAL.get(from + ":" + action))));
    }

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("allCombinations")
    @DisplayName("42 种组合：合法性判断必须与转移表一致")
    void 组合校验(TicketStatus from, TicketAction action, TicketStatus expectedTo) {
        if (expectedTo == null) {
            assertFalse(machine.isAllowed(from, action), "不该允许：" + from + " --" + action);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> machine.require(from, action), "非法转移必须抛异常");
            assertEquals("TICKET_ILLEGAL_TRANSITION", ex.getCode().name());
        } else {
            assertTrue(machine.isAllowed(from, action), "应该允许：" + from + " --" + action);
            Transition t = machine.require(from, action);
            assertEquals(expectedTo, t.to(), "目标状态不符");
            assertEquals(from, t.from(), "起始状态不符");
            assertEquals(action, t.action(), "动作不符");
        }
    }

    @Test
    @DisplayName("CANCELLED 是终态：任何动作都不允许")
    void 终态不可流转() {
        for (TicketAction action : TicketAction.values()) {
            assertFalse(machine.isAllowed(TicketStatus.CANCELLED, action));
        }
    }

    @Test
    @DisplayName("转移表不应包含重复键（重复 put 会静默覆盖）")
    void 转移表无重复() {
        long expected = 6L * 7L;
        assertEquals(expected, allCombinations().count(), "组合总数应为 42");
        assertEquals(10, LEGAL.size(), "合法转移应为 10 条");
    }
}
