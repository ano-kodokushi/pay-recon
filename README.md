# 支付订单与对账模块（pay-recon）

**一句话定位**：一个「下单 → 支付回调 → 状态机 → 对账核销」的支付闭环，重点解决**重复回调与并发场景下的重复扣款**。

技术栈：Java 17 + Spring Boot 3.3.0 + MyBatis-Plus 3.5.7 + MySQL 8 + Maven。
单机可跑，不依赖 MQ / 分布式事务 / 注册中心 / 前端页面。

---

## 1. 架构（文字版）

```
                  ┌──────────────────────────────────────────────────────────┐
                  │                    pay-recon (单进程)                     │
                  │                                                          │
  商户侧           │  OrderController          CallbackController            │
  POST /api/order/ │  POST /api/order/create   POST /api/pay/callback         │
       create ────┼──────► OrderService ──┐        │                         │
                  │   (唯一索引幂等)        │        │ 1) HMAC 验签             │
                  │                        │        │ 2) 先落 pay_flow 原文     │
                  │                        │        ▼                         │
                  │                        │   ┌───────────────────────────┐  │
                  │                        └──►│  AdvanceService.advance() │◄─┼──┐
                  │                            │  ★ 全项目唯一的推进入口     │  │  │
                  │                            │  1. 落 pay_flow 原始报文    │  │  │
                  │                            │  2. OrderStateMachine 校验  │  │  │
                  │                            │  3. 条件 UPDATE + 影响行数   │  │  │
                  │                            │  4. =1 → 入账 + 推进流水     │  │  │
                  │                            │  5. =0 → 重复被忽略流水      │  │  │
                  │                            └───────────┬───────────────┘  │  │
                  │                                        │                  │  │
                  │            ┌───────────────────────────┼──────────────┐   │  │
                  │            ▼                           ▼              ▼   │  │
                  │    QueryJobScheduler            ReconcileService    Account│  │
                  │    (@Scheduled 兜底扫描)         (差异发现 + 补偿)     Service│  │
                  │            │                           │          (入账)  │  │
                  │            └───────────────┬───────────┘                  │  │
                  │                            ▼                              │  │
                  │                   ChannelBillStore / ChannelClient        │  │
                  │                   (渠道 Mock，可注入故障)                  │  │
                  └──────────────────────────────────────────────────────────┘  │
                                                                                │
   MySQL 8:  pay_order ── pay_flow(只追加) ── reconcile_detail ── account_entry ─┘
                                                                  pay_account
```

**四条主路径**

| 路径 | 入口 | 要点 |
|---|---|---|
| 任务1 下单幂等 | `POST /api/order/create` | 靠 `uk_merchant_order_no` + 精确捕 `DuplicateKeyException` |
| 任务2 支付回调 | `POST /api/pay/callback` | 验签 → 先落原文 → 状态机 → 按影响行数决定是否入账 → **恒返成功** |
| 任务3 主动查询兜底 | `@Scheduled` | 扫超时 `PAYING`，**复用** `AdvanceService.advance()`，不复制逻辑 |
| 任务4 对账 | `POST /api/reconcile/run` | 差异发现 + 只补偿"渠道成功/我方非终态"这一安全情形 |
| 任务5 渠道 Mock | `/mock/channel/**` | 可注入丢回调、强制查询状态、注入对账单差异 |

**状态机（集中实现，`OrderStateMachine`）**

```
CREATED ──► PAYING ──► SUCCESS（终态）
   │           │
   │           └──► FAILED（终态）
   └──► CLOSED（终态）
   且 CREATED ──► SUCCESS 合法（渠道可同步返回成功，无中间态）
```

---

## 2. 数据模型

| 表 | 作用 | 关键点 |
|---|---|---|
| `pay_order` | 支付订单 | `UNIQUE KEY uk_merchant_order_no` —— **幂等的唯一来源** |
| `pay_flow` | 流水/事件 | **只追加，永不修改、永不删除**；`raw_payload` 存渠道原文 |
| `reconcile_detail` | 对账差异 | 4 类 `diff_type`；`resolved` 标记是否已处理 |
| `pay_account` | 我方账户（单账户） | `balance DECIMAL(18,2)` |
| `account_entry` | 入账流水 | `UNIQUE KEY uk_order_id` —— **幂等入账的唯一来源** |

> 后两张表是在规格 §2 之外**新增**的（原三张表字段一个没减）。
> 理由：规格要求证明"入账动作只执行 1 次"。只看状态位只能间接推断，
> 把入账做成一条带唯一索引的流水后，就能用
> `SELECT COUNT(*) FROM account_entry WHERE order_id = ?` 直接数出来。

金额口径：**全部 `DECIMAL(18,2)` + `BigDecimal`**，全工程不存在 `float`/`double` 表示金额。

---

## 3. 设计取舍（规格要求能口头解释的四点）

### 3.1 为什么幂等靠唯一索引，而不是应用层判空

因为"先查后插"在并发下必然失败：两个线程**同时**执行 `selectByNo`，都读到 null，
然后各插一笔，得到两行、两个 `order_id`。应用层的判空检查与插入之间没有任何互斥，
判空结果在插入时就已经过期了。

正确做法是把**判定权交给数据库**：`merchant_order_no` 上建唯一索引，
插入时由 InnoDB 保证只有一个成功，另一个抛 `DuplicateKeyException`，
应用层精确捕获它并回查返回已存在的那一笔。

两个实现细节：
- **必须精确捕获 `DuplicateKeyException`**，不能用 `catch (Exception)` 兜网 ——
  否则真实的数据库故障（连接断开、死锁）会被当成"订单已存在"，静默返回错误结果。
- 竞争失败方可能在赢家**提交之前**就回查，此时合法地读到 null。
  代码用有界的重试（最多 20 次 × 10ms）等待赢家提交，超时则明确报错，
  而不是把它当成"订单不存在"再插一次。

### 3.2 为什么回调要落原始报文

三个理由：
1. **可追溯**：事后能回答"渠道当时到底发了什么"，而不是只知道"状态变了"。
2. **可复算**：状态机逻辑或金额口径变更后，可以拿原文重放，验证结论是否一致。
3. **审计要求**：资金相关的每一次状态变化都必须有不可篡改的来源原文。

因此顺序是**验签 → 先落 `pay_flow` 原文 → 再处理业务**，不可颠倒。
并且这一行是**无条件**写的：重复回调也要留痕，否则"渠道重复推了几次"就无从考证。

### 3.3 为什么不用分布式事务

渠道方**不参与我方事务**：它自己的账已经记完，不会因为我方 `ROLLBACK` 而撤销。
用一个分布式事务去"协调"一个根本不加入的参与者，既无意义又增加故障面。

所以走**最终一致**：状态机保证单库内的状态迁移合法且幂等，
对账负责在事后发现"渠道成功但我方没成功"这类不一致，并通过补偿（复用同一个状态机）修复。
"我方成功、渠道无此单"这种可能涉及真实资金差错的情形**只标记不自动改状态**，留人工处理。

### 3.4 为什么回调处理失败也要返回成功

渠道的通用策略是：**没收到成功应答就认为通知失败，然后按退避策略无限重试**。
如果我方因为自身异常（数据库抖动、下游超时）返回失败：
- 渠道会持续重推同一笔，放大我方压力，形成"越忙越重试、越重试越忙"的正反馈；
- 而且这笔钱其实已经收到了，返回失败在语义上也是错的。

所以回调接口**所有分支都返回成功语义**（验签失败、报文无法解析、非法迁移、未预期异常），
同时把真实问题记进日志和 `pay_flow`。真正需要对账的问题由**对账**兜底，而不是靠让渠道重试。

这也是代码中**唯一**允许 `catch (Exception)` 的地方（`CallbackController`），
与 3.1 中禁止的兜网并不矛盾：这里兜的是"接口不能失败"这个约束，而不是业务判定。

---

## 4. 已知限制与未做的事

**本工程明确没有做（规格 §7 要求）**：前端/管理后台/任何页面、用户注册登录与权限、
真实第三方渠道对接、真实退款、多渠道路由、分账清算、分币种、手续费、发票、
MQ、分布式事务（Seata 等）、微服务拆分、注册中心、分库分表。

**具体的技术限制（逐条）**：

1. **`pay_flow` 无归档与清理策略**。表会随回调量线性增长，当前没有任何分区、归档或 TTL。
2. **对账按账单日全量比对，且重复执行会重复插入差异明细**。`reconcile()` 不做去重
   （差异明细是审计证据，因此也没有提供 DELETE 清理路径）。生产上需要按账单日加唯一约束或加幂等键。
3. **渠道 Mock 的状态全在进程内存里**（`ChannelBillStore` 用 `ConcurrentHashMap`）。
   进程重启即丢失，多实例部署时各实例状态互不可见。它只是测试用的故障注入底座，
   不是可用的渠道对接实现。
4. **`pay_account` 是单账户模型**（`ACC-001`，代码里 `resolveAccountNo()` 目前返回常量，
   尚未接入 `app.reconcile.account-no` 配置）。多商户/多账户场景未实现。
5. **`AdvanceService.advance()` 在一个事务里"写流水 + 条件更新 + 入账"**。
   当前靠条件更新的影响行数来裁决并发，这是正确的；但事务较长，
   高并发下会有行锁竞争。生产上可考虑缩短事务或改为"先抢占、后入账"两步。
   另外 `AdvanceService` 里读取订单、写流水、更新状态在同一个事务内，
   依赖 InnoDB 的 `READ COMMITTED` 快照，未显式设置隔离级别。
6. **主动查询兜底的扫描是"捞一批、逐条问渠道"**，没有并发处理，
   批次上限由 `app.query-job.batch-size` 控制（默认 200）。订单量大时需要分片或并发。
7. **对账没有"我方无、渠道有"的自动建单**，一律记为 `ONE_SIDE_ONLY` 且 `resolved=0`。
8. **没有失败告警、没有监控指标（Micrometer）、没有链路追踪**。异常只进日志。
9. **回调验签是 mock HMAC**（`HmacSHA256(secret, rawBody)`，无时间戳、无随机串、无防重放窗口）。
   真实渠道的验签还包含证书、时间戳有效期、`nonce` 防重放等，均未实现。
10. **没有做限流与幂等令牌**：回调接口对同一订单的重复调用会被处理（然后正确地忽略），
    但在极端流量下没有入口限流保护。
11. **`reconcile_detail` 只有 `resolved` 一个标志位**，没有记录处理人、处理时间、处理备注，
    不构成完整的人工处理工单闭环。

---

## 5. 如何运行

**前置**：JDK 17、MySQL 8（本机 3306）、Maven。

```powershell
# 1) 初始化数据库与表（建库 + 建表 + 初始化账户 + 回显唯一索引）
.\scripts\init-db.ps1

# 2) 启动应用（默认 8080，配置见 src/main/resources/application.yml）
mvn spring-boot:run

# 3) 端到端冒烟：下单 → 渠道收款 → 回调 → 入账 → 重放 5 次 → 非法迁移
.\scripts\smoke.ps1
```

数据库连接默认 `root` / `000000`、库名 `pay_recon`。**口令等敏感项不写死在代码里**，
而是走环境变量（`application.yml` 里是 `${DB_PASSWORD:000000}` 这种形式，默认值仅供本机 demo）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DB_HOST` | `127.0.0.1` | MySQL 主机 |
| `DB_PORT` | `3306` | 端口 |
| `DB_NAME` | `pay_recon` | 库名 |
| `DB_USER` | `root` | 用户名 |
| `DB_PASSWORD` | `000000` | 口令（**部署时请覆盖**） |

`scripts/init-db.ps1` 与 `scripts/smoke.ps1` 读同一组环境变量，例如：

```powershell
$env:DB_PASSWORD = '<你的口令>'
.\scripts\init-db.ps1
```

新建库时把 `schema.sql` 喂给 MySQL 即可，**应用不会在启动时自动建表**
（`spring.sql.init.mode=never`）—— 因为 `pay_flow` 是只追加的审计表，
把"启动即重放 DDL"放进启动路径风险太高。

### 跑测试

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.8.101-hotspot"
mvn -o test
```

实测结果：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

> **离线注意**：本机没有外网，而 Surefire 运行 JUnit 5 所需的
> `org.apache.maven.surefire:surefire-junit-platform` 不在本地 Maven 仓，官方 jar 无法下载。
> 工程内提供了一份**行为等价的实现**（源码在 `surefire-provider-shim/`），
> 用 `scripts/build-surefire-shim.ps1` 可重新编译并安装到本地仓，坐标与官方一致。
> 换到有网的机器不需要任何特殊处理，`mvn test` 会直接使用官方 provider。

测试结果与每个场景的真实数字见 **[TEST_REPORT.md](TEST_REPORT.md)**。

---

## 6. 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/order/create` | 下单（幂等），body `{merchantOrderNo, amount}` |
| POST | `/api/pay/callback` | 支付回调，需头 `X-Signature`（`HmacSHA256(secret, rawBody)` 的小写十六进制）；**恒返回 200 成功语义** |
| POST | `/api/reconcile/run` | 对账（差异发现 + 补偿），body `{billDate: "yyyy-MM-dd"}` |
| GET | `/api/reconcile/details?billDate=` | 查询某账单日差异明细 |
| POST | `/mock/channel/pay` | 渠道 Mock：收款 |
| POST | `/mock/channel/query` | 渠道 Mock：查单 |
| POST | `/mock/channel/notify` | 渠道 Mock：发回调，支持 `times` 重复发送 |
| POST | `/mock/channel/control/drop-callback?drop=` | 故障注入：丢回调开关 |
| POST | `/mock/channel/control/force-status?merchantOrderNo=&status=` | 故障注入：强制查询返回状态（含 `NOT_FOUND`） |
| POST | `/mock/channel/control/statement?merchantOrderNo=&status=&amount=` | 故障注入：注入渠道对账单行 |
| POST | `/mock/channel/control/reset` | 清空渠道 Mock 全部内存状态 |
| GET | `/mock/channel/control/snapshot` | 渠道 Mock 当前状态快照 |
