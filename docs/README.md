# 文档导航

工程还没开始写代码，先把“做什么、怎么做、做到哪了、怎么验”分开写清楚。

| 顺序 | 文档 | 回答什么问题 | 什么时候看 |
| --- | --- | --- | --- |
| 1 | [01-requirements.md](01-requirements.md) | 系统要做什么，什么不做 | 有需求争议时 |
| 2 | [总设计计划.md](总设计计划.md) | 为什么是这个架构，12 条技术决策的取舍 | 想改技术方案前 |
| 3 | [02-design.md](02-design.md) | 表怎么建、接口怎么排、状态机怎么转 | 写代码时对照 |
| 4 | [总实现路径.md](总实现路径.md) | 按什么顺序做，什么时候算做完 | 每开始一个阶段 |
| 5 | [04-progress.md](04-progress.md) | 十个模块的进度与当前卡点 | 每次开工前 |
| 6 | [05-test-plan.md](05-test-plan.md) | 每个模块测什么、怎么跑、失败怎么定位 | 写完一个模块 |
| 7 | [03-build-guide.md](03-build-guide.md) | 具体敲什么命令、装什么依赖 | 动手时 |
| 8 | [M01-框架搭建教程.md](M01-框架搭建教程.md) | M01 每一步做什么、为什么、怎么验 | 开始 M01 时 |

## 目录结构

```
docs/
├── README.md               本文件
├── 01-requirements.md      需求（MVP 范围 + 版本路线）
├── 总设计计划.md            架构决策与开源调研
├── 02-design.md            技术设计（表、路由、状态机、Mapper）
├── 总实现路径.md            阶段划分、门禁、返工信号
├── 04-progress.md          进度看板（勾选式）
├── 05-test-plan.md         测试方案与用例清单
├── 03-build-guide.md       执行手册（命令级）
├── M01-框架搭建教程.md      M01 最小骨架（可跟做）
└── test-skeletons/         可复制的测试骨架（4 个 .java）
scripts/
└── smoke.sh                部署后冒烟脚本（curl）
```

## 模块与文档的对应

| 模块 | 主要参考 | 测试 |
| --- | --- | --- |
| M01 框架 | `M01-框架搭建教程.md`；03 §1.1 | `HealthTest`、`curl /actuator/health` |
| M02 数据库 | 03 §1.2–1.4；02 §2.2 | smoke `--only=db` |
| M03 数据访问 | 03 §1.5；02 §6 | `TicketMapperIT` |
| M04 页面 | 03 §1.7；02 §5.1 | smoke |
| M05 认证 | 03 §2.1–2.3；总设计 D9 | `TicketAccessIT` |
| M06 授权 | 03 §2.4；02 §4.2；总设计 D8 | `TicketAccessIT` |
| M07 写路径 | 03 §3；02 §6.3 | `TicketFlowIT` |
| M08 状态机 | 02 §3；总设计 D6 | `TicketStateMachineTest` |
| M09 回复与详情 | 02 §3.3、§7 | `TicketFlowIT` |
| M10 交付 | 03 §4 | `./mvnw verify` |
