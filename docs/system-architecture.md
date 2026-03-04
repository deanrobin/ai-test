# 技术架构文档：Polymarket × OKX 对冲套利系统

**版本：** v0.1  
**日期：** 2026-03-04  
**状态：** 设计阶段

---

## 1. 系统全景

### 1.1 设计原则

- **数据驱动**：所有决策基于实时数据，不依赖人工判断
- **模块解耦**：各模块通过消息总线通信，可独立部署/升级
- **风控优先**：风控模块拥有最高优先级，可随时中断任何操作
- **可观测性**：每一步操作均有日志、指标、告警覆盖

### 1.2 总体架构图

```
外部数据源
  ├── Polymarket WebSocket / REST
  ├── OKX WebSocket / REST
  └── Polygon 链上事件

          ↓
┌─────────────────────────────────────────────────────┐
│                   数据观测层                          │
│  行情采集 · 链上监听 · 数据标准化 · 本地缓存           │
└────────────────────┬────────────────────────────────┘
                     ↓ (标准化行情事件)
┌─────────────────────────────────────────────────────┐
│                   盘口计算引擎                         │
│  概率转换 · IV 计算 · Greeks · 套利信号生成            │
└────────────────────┬────────────────────────────────┘
                     ↓ (套利/对冲信号)
              ┌──────┴──────┐
              ↓             ↓
┌─────────────────┐  ┌─────────────────────────────┐
│    风控层        │  │       执行层                  │
│ 仓位检查        │  │  Poly 链上交易 · OKX 下单     │
│ 限额验证        │→→│  智能路由 · 滑点控制           │
│ 一键清仓        │  │  对冲比例执行                 │
└─────────────────┘  └──────────────┬──────────────┘
                                     ↓
┌─────────────────────────────────────────────────────┐
│                   仓位管理层                          │
│  持仓记录 · 盈亏计算 · 对冲状态追踪 · Delta 再平衡     │
└────────────────────┬────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────┐
│                   结算模块                            │
│  提前平仓 · 止盈止损 · 事件结算处理 · 资金归集         │
└────────────────────┬────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────┐
│                   监控 & 告警层                        │
│  实时 PnL · 系统健康 · 异常告警 · 操作审计日志         │
└─────────────────────────────────────────────────────┘
```

---

## 2. 数据观测层

### 2.1 职责

负责从所有外部数据源采集原始数据，标准化后推送给下游。

### 2.2 子模块

#### 2.2.1 Polymarket 行情采集器

```
数据项：
  - 市场列表（事件名称、条件、到期时间）
  - YES/NO 实时价格（Bid/Ask/Last）
  - 成交量、持仓量
  - 市场状态（Active / Resolved / Paused）

采集方式：
  - 行情：WebSocket 订阅（低延迟）
  - 市场元数据：REST 轮询（每 5 分钟）
  - 链上结算事件：Polygon 事件监听

数据结构（标准化后）：
{
  market_id: string,
  condition_id: string,
  event_title: string,
  outcome: "YES" | "NO",
  best_bid: float,       // 买一价
  best_ask: float,       // 卖一价
  last_price: float,
  volume_24h: float,
  expire_time: timestamp,
  status: enum,
  ts: timestamp
}
```

#### 2.2.2 OKX 期权行情采集器

```
数据项：
  - 期权链（所有 Strike × Expiry 组合）
  - 实时 Bid/Ask/IV
  - Greeks（Delta、Gamma、Vega、Theta）
  - 标的现货价格（BTC/ETH/SOL）
  - 资金费率、基差

采集方式：
  - 期权行情：WebSocket 订阅（逐档更新）
  - Greeks：REST 批量拉取（每 10 秒）
  - 现货价格：WebSocket ticker

数据结构（标准化后）：
{
  instrument_id: string,   // e.g. BTC-20260328-100000-C
  underlying: string,
  strike: float,
  expiry: date,
  option_type: "C" | "P",
  best_bid: float,
  best_ask: float,
  iv: float,               // 隐含波动率
  delta: float,
  gamma: float,
  vega: float,
  theta: float,
  spot_price: float,
  ts: timestamp
}
```

#### 2.2.3 链上事件监听器

```
监听内容：
  - Polymarket CTF 合约：市场创建、条件解析、资金赎回
  - ERC-1155 转账事件（持仓变化）
  - UMA OptimisticOracle：价格提议、争议、结算

工具：
  - web3.py event filter
  - Alchemy/QuickNode Webhook（降低轮询压力）
```

#### 2.2.4 本地数据缓存

```
Redis（热数据，<1s 延迟）：
  - 当前行情快照
  - 活跃市场列表
  - 计算中间结果

TimescaleDB（历史时序数据）：
  - 行情 OHLC
  - 套利信号历史
  - PnL 时序
```

---

## 3. 盘口计算引擎

### 3.1 职责

消费标准化行情数据，输出定价偏差信号、对冲比例建议、风险指标。

### 3.2 子模块

#### 3.2.1 概率转换模块

将不同来源的"价格"统一转换为风险中性概率，作为比较基准。

```python
class ProbabilityConverter:
    
    def poly_to_prob(self, yes_price: float) -> float:
        """
        Polymarket YES 价格直接视为概率
        （已内置市场摩擦折扣）
        """
        return yes_price
    
    def okx_option_to_prob(self, S, K, T, iv, r=0) -> float:
        """
        OKX Call 的风险中性执行概率 N(d2)
        S: 现货价
        K: 行权价
        T: 到期年化
        iv: 隐含波动率
        """
        if T <= 0 or iv <= 0:
            return 1.0 if S >= K else 0.0
        d2 = (log(S/K) + (r - 0.5*iv**2)*T) / (iv*sqrt(T))
        return norm.cdf(d2)
    
    def spread(self, poly_prob, okx_prob) -> float:
        """
        套利价差（正数 = Poly 高估）
        """
        return poly_prob - okx_prob
```

#### 3.2.2 IV 曲面构建

```
输入：OKX 全期权链行情（所有 Strike × Expiry）
输出：IV Surface（插值曲面）

方法：
  - 对每个 Expiry 构建 IV Smile（SVI 模型拟合）
  - 跨 Expiry 做时间维度插值
  - 用于反推任意 Strike/T 的理论 IV

作用：
  - 为 Poly 市场匹配最接近的 OKX 期权
  - 避免使用流动性差的深度虚值期权
```

#### 3.2.3 套利信号生成器

```python
class ArbitrageSignalEngine:
    
    SPREAD_THRESHOLD = 0.05     # 最小价差阈值 5%
    MIN_LIQUIDITY_USD = 1000    # 最小流动性要求
    
    def evaluate(self, poly_market, okx_option) -> Signal | None:
        
        poly_prob = self.converter.poly_to_prob(poly_market.last_price)
        okx_prob  = self.converter.okx_option_to_prob(...)
        spread    = self.converter.spread(poly_prob, okx_prob)
        
        if abs(spread) < self.SPREAD_THRESHOLD:
            return None
        
        # 流动性检查
        poly_liq = poly_market.best_ask - poly_market.best_bid
        if poly_liq > 0.05:   # 价差太大，流动性不足
            return None
        
        return Signal(
            signal_type = "POLY_OVERPRICED" if spread > 0 else "POLY_UNDERPRICED",
            spread      = spread,
            poly_market = poly_market,
            okx_option  = okx_option,
            suggested_size = self.calc_size(spread),
            expected_pnl   = self.estimate_pnl(spread, ...),
            confidence     = self.calc_confidence(poly_market, okx_option),
            ts             = now()
        )
```

#### 3.2.4 对冲比例计算器

```python
class HedgeRatioCalculator:
    
    def calc_delta_hedge(self, poly_position, okx_chain) -> HedgeOrder:
        """
        为 Polymarket 仓位计算所需的 OKX 对冲量
        
        Poly YES 仓位的隐含 Delta：
          Delta_poly ≈ dP/dS × position_size
          
        对冲方向：
          持有 YES → 卖出 Call（或买入 Put）
          持有 NO  → 买入 Call（或卖出 Put）
        """
        poly_delta = self.estimate_poly_delta(poly_position)
        
        # 找最优对冲期权（Delta 最接近，流动性最好）
        best_option = self.find_hedge_option(
            target_delta = -poly_delta,
            okx_chain    = okx_chain,
            expiry_match = poly_position.expire_time
        )
        
        return HedgeOrder(
            instrument = best_option,
            size       = abs(poly_delta / best_option.delta),
            direction  = "SELL" if poly_delta > 0 else "BUY"
        )
```

---

## 4. 风控层

> **最高优先级模块。任何执行操作前必须通过风控校验。**

### 4.1 仓位风控

```yaml
limits:
  max_single_position_usd: 5000      # 单笔最大下单
  max_total_exposure_usd: 50000      # 总敞口上限
  max_poly_markets: 10               # 最多同时持有 Poly 市场数
  max_okx_contracts: 20              # 最多同时持有 OKX 合约数
  max_single_market_concentration: 0.2  # 单市场不超过总仓位 20%
```

### 4.2 损益风控

```yaml
risk:
  max_drawdown_pct: 0.15             # 最大回撤 15%，触发暂停
  daily_loss_limit_usd: 3000         # 单日亏损上限
  stop_loss_per_trade_pct: 0.10      # 单笔止损 10%
  take_profit_per_trade_pct: 0.05    # 单笔止盈 5%（可配置）
```

### 4.3 一键清仓（Emergency Kill Switch）

```
触发方式：
  1. 手动触发（Telegram 发送指令 /killswitch）
  2. 自动触发：
     - 当日亏损 > daily_loss_limit
     - 总回撤 > max_drawdown_pct
     - 系统异常（行情断连 > 30s、执行失败 > 3 次）

清仓流程：
  Step 1: 立即暂停所有新信号生成
  Step 2: 取消所有未成交挂单（OKX + Poly）
  Step 3: 市价平仓所有 OKX 期权仓位（优先）
  Step 4: 尝试市价卖出 Polymarket 仓位
          └─ 若无买盘：记录持仓，等待事件自然结算
  Step 5: 生成清仓报告，推送告警
  Step 6: 系统进入 HALTED 状态，等待人工确认恢复

清仓状态机：
  RUNNING → (触发条件) → KILLING → HALTED → (人工确认) → RUNNING
```

### 4.4 异常价格过滤

```python
class PriceGuard:
    """
    防止因行情异常或 API 故障导致的错误下单
    """
    def validate(self, price: float, market: str) -> bool:
        last_valid = self.cache.get_last_valid(market)
        
        # 价格跳变超过 20% → 拒绝
        if abs(price - last_valid) / last_valid > 0.20:
            self.alert(f"价格异常跳变: {market} {last_valid} → {price}")
            return False
        
        # Poly YES + NO 之和偏离 $1 过多 → 拒绝
        yes_price = self.cache.get(f"{market}_YES")
        no_price  = self.cache.get(f"{market}_NO")
        if abs(yes_price + no_price - 1.0) > 0.05:
            self.alert(f"Poly 价格不自洽: YES={yes_price} NO={no_price}")
            return False
        
        return True
```

### 4.5 Gas 费风控（Poly 链上操作）

```
规则：
  - 估算 Gas 费 > 交易金额 1% → 拒绝下单
  - Polygon Gas Price > 500 Gwei → 暂停链上操作
  - 自动设置 Gas Limit 上限，防止意外高 Gas 消耗
```

---

## 5. 执行层

### 5.1 Polymarket 执行器

```
协议栈：
  Polymarket CLOB API（中心化撮合，低延迟）
  └─ 底层：0x Protocol + Polygon 链上结算

操作：
  - 查询订单簿深度
  - 市价单 / 限价单
  - 撤单
  - 查询持仓

滑点控制：
  - 下单前检查订单簿，估算成交价
  - 设置最大可接受滑点（默认 0.5%）
  - 超出滑点 → 拆单或放弃
```

### 5.2 OKX 执行器

```
接口：OKX v5 REST API + WebSocket

操作：
  - 期权下单（市价/限价）
  - 撤单 / 改单
  - 查询持仓 / 委托
  - 账户余额查询

下单策略：
  - 优先挂 Maker 限价单（省手续费）
  - 超时未成交（>5s）→ 改市价单
  - 大单拆分：超过盘口深度 30% 时分批执行
```

### 5.3 智能路由

```python
class SmartOrderRouter:
    """
    对于同一信号，选择最优执行路径
    """
    def route(self, signal: Signal) -> ExecutionPlan:
        
        # 评估 Poly 执行成本
        poly_cost = self.estimate_poly_cost(signal)  # 含 Gas + 滑点
        
        # 评估 OKX 执行成本  
        okx_cost  = self.estimate_okx_cost(signal)   # 含手续费 + 滑点
        
        # 计算净预期收益
        net_pnl = signal.expected_pnl - poly_cost - okx_cost
        
        if net_pnl < MIN_NET_PNL:
            return ExecutionPlan(action="SKIP", reason="成本超过收益")
        
        return ExecutionPlan(
            poly_order = self.build_poly_order(signal),
            okx_order  = self.build_okx_order(signal),
            execute_sequence = "POLY_FIRST"  # 先执行流动性差的一侧
        )
```

---

## 6. 仓位管理层

### 6.1 职责

追踪所有持仓状态，计算实时 PnL，管理对冲比例是否需要再平衡。

### 6.2 仓位数据结构

```python
@dataclass
class Position:
    position_id: str
    
    # Polymarket 仓位
    poly_market_id: str
    poly_outcome: Literal["YES", "NO"]
    poly_shares: float          # 持有份额数
    poly_avg_cost: float        # 平均成本
    poly_current_price: float   # 当前市价
    
    # OKX 对冲仓位
    okx_instrument: str
    okx_size: float             # 合约数量
    okx_direction: str          # LONG / SHORT
    okx_avg_cost: float
    okx_current_price: float
    
    # 状态
    status: Literal["OPEN", "PARTIAL_HEDGE", "FULLY_HEDGED", "CLOSING", "CLOSED"]
    hedge_ratio: float          # 当前对冲比例
    target_hedge_ratio: float   # 目标对冲比例
    
    # 盈亏
    unrealized_pnl: float
    realized_pnl: float
    total_fees: float
    
    # 时间
    open_time: datetime
    last_rebalance_time: datetime
    expire_time: datetime       # Poly 市场到期时间
```

### 6.3 Delta 再平衡

```
触发条件（满足任一）：
  - 当前 hedge_ratio 偏离目标 > 10%
  - 标的价格变动 > 5%
  - 距到期日 < 3 天（加快再平衡频率）
  - 定时：每小时检查一次

再平衡逻辑：
  1. 重新计算 Poly 仓位的隐含 Delta
  2. 计算所需 OKX 对冲调整量
  3. 评估调整成本（手续费 + 滑点）
  4. 若调整成本 < 收益提升 → 执行再平衡
```

---

## 7. 结算模块

### 7.1 提前平仓（套利收益锁定）

```
触发逻辑：
  当两市场价差已大幅收敛（如从 8% 收敛到 2%）时，
  主动平仓锁定利润，无需等待事件最终结算。

流程：
  1. 监控持仓的实时价差（vs 开仓时价差）
  2. 当价差收敛率 >= 60%（或浮盈 >= 止盈阈值）→ 触发平仓
  3. 同时平 Poly 仓位 + OKX 对冲仓位
  4. 记录实现利润
```

### 7.2 止损平仓

```
触发条件：
  - 单笔浮亏 > stop_loss_per_trade_pct
  - 价差反向扩大（信号失效）
  
处理：
  - 优先平流动性好的仓位（OKX）
  - Poly 视流动性决定是否市价平仓或持有到结算
```

### 7.3 到期结算处理

```
Polymarket 事件结算流程：
  T-1d: 检查 UMA Oracle 提议状态
  T+0:  事件触发 → 等待链上确认
  T+1h: 确认结算结果
  T+2h: 执行链上赎回（Redeem 获取 USDC）
  
  异常处理：
    - Oracle 争议：暂停相关仓位操作，监控争议进展
    - 结算延迟：延长对应 OKX 对冲到期日
    
OKX 期权到期：
  - 到期前 1 小时：评估是否主动平仓（避免强制结算滑点）
  - 深度实值期权：可选择行权
```

### 7.4 资金归集

```
每次结算后：
  1. 统计该仓位总 PnL（含 Poly 结算 + OKX 平仓）
  2. 计算手续费、Gas 费汇总
  3. 归还基础仓位资金池
  4. 写入结算日志
  5. 更新策略绩效统计
```

---

## 8. 监控 & 告警层

### 8.1 实时监控指标

```
账户层面：
  - 总资产 / 可用资金 / 保证金使用率
  - 当日 PnL / 本周 PnL / 总 PnL
  - 当前开放仓位数 / 待执行信号数

仓位层面：
  - 每个仓位：持仓成本 / 当前价值 / 浮盈亏
  - 对冲偏离度（hedge_ratio vs target）
  - 距到期时间

系统层面：
  - 行情连接状态（Poly WS / OKX WS / Polygon RPC）
  - 信号处理延迟（P50 / P99）
  - 下单成功率 / 失败率
  - 最近 10 笔交易状态
```

### 8.2 告警规则

```yaml
alerts:
  - name: 连接断开
    condition: ws_disconnect_seconds > 30
    severity: CRITICAL
    action: [telegram_notify, pause_trading]
    
  - name: 单日亏损告警
    condition: daily_pnl < -daily_loss_limit * 0.7
    severity: HIGH
    action: [telegram_notify]
    
  - name: 单日亏损触发止损
    condition: daily_pnl < -daily_loss_limit
    severity: CRITICAL
    action: [telegram_notify, trigger_killswitch]
    
  - name: 对冲偏离过大
    condition: any(position.hedge_drift > 0.15)
    severity: MEDIUM
    action: [telegram_notify, trigger_rebalance]
    
  - name: 价格异常
    condition: price_guard.anomaly_detected
    severity: HIGH
    action: [telegram_notify, reject_order]
    
  - name: 高 Gas 费
    condition: polygon_gas_gwei > 500
    severity: LOW
    action: [telegram_notify, pause_poly_orders]
```

### 8.3 Telegram 操作指令

```
查询类：
  /status          - 系统状态总览
  /positions       - 当前持仓列表
  /pnl             - 今日 / 本周 PnL
  /signals         - 最近套利信号
  /health          - 系统健康检查

操作类：
  /killswitch      - 一键清仓（需二次确认）
  /pause           - 暂停新信号（保留现有仓位）
  /resume          - 恢复交易
  /close <id>      - 手动平仓指定仓位
  /rebalance <id>  - 手动触发对冲再平衡

配置类：
  /setlimit <key> <value>  - 调整风控参数
  /threshold <pct>         - 修改套利触发阈值
```

### 8.4 操作审计日志

```
每次操作记录：
  - 时间戳
  - 操作类型（信号生成 / 下单 / 撤单 / 风控触发 / 人工操作）
  - 操作者（系统 / Telegram 用户 ID）
  - 操作参数
  - 结果（成功/失败/原因）
  
存储：PostgreSQL（永久保留）
查询：/audit <date> 或 /audit <position_id>
```

---

## 9. 配置管理

### 9.1 配置文件结构

```yaml
# config.yaml

# 数据源
data:
  polymarket_ws: "wss://ws-subscriptions-clob.polymarket.com/ws/market"
  okx_ws: "wss://ws.okx.com:8443/ws/v5/public"
  polygon_rpc: "https://polygon-mainnet.g.alchemy.com/v2/${ALCHEMY_KEY}"
  refresh_interval_seconds: 10

# 策略参数  
strategy:
  arb_spread_threshold: 0.05     # 套利触发价差
  min_liquidity_usd: 1000
  max_poly_bid_ask_spread: 0.05
  hedge_rebalance_threshold: 0.10
  preferred_expiry_days: [7, 14, 30]

# 风控
risk:
  max_single_position_usd: 5000
  max_total_exposure_usd: 50000
  max_drawdown_pct: 0.15
  daily_loss_limit_usd: 3000
  stop_loss_pct: 0.10
  take_profit_pct: 0.05
  max_gas_gwei: 500
  max_gas_cost_pct: 0.01

# 结算
settlement:
  convergence_exit_ratio: 0.60   # 价差收敛 60% 时平仓
  pre_expiry_close_hours: 1      # 到期前 1 小时主动平仓

# 通知
notify:
  telegram_chat_id: "${TELEGRAM_CHAT_ID}"
  alert_levels: [MEDIUM, HIGH, CRITICAL]
```

---

## 10. 技术选型汇总

| 模块 | 技术 | 理由 |
|------|------|------|
| 主语言 | Python 3.11+ | 生态丰富，金融库完善 |
| 异步框架 | asyncio + aiohttp | 多路 WebSocket 并发 |
| 数学计算 | numpy, scipy, QuantLib | 期权定价、IV 计算 |
| 热缓存 | Redis 7 | 行情快照、中间结果 |
| 时序数据库 | TimescaleDB | 行情历史、PnL 时序 |
| 关系型数据库 | PostgreSQL | 仓位、审计日志 |
| 链上交互 | web3.py + ethers.js | Polygon 合约调用 |
| 容器化 | Docker Compose | 本地开发 / 云部署一致 |
| 监控面板 | Grafana | 实时指标可视化 |
| 告警通道 | Telegram Bot | 已有基础设施复用 |
| 测试 | pytest + backtesting.py | 单元测试 + 历史回测 |

---

## 11. 开发阶段规划

### Phase 1（2 周）：数据观测 + 信号可视化
- [ ] Polymarket 行情采集器
- [ ] OKX 期权行情采集器
- [ ] 概率转换模块
- [ ] 套利信号生成（仅输出，不执行）
- [ ] Telegram `/signals` 查询

### Phase 2（2 周）：手动执行 + 风控
- [ ] Polymarket 执行器（手动确认模式）
- [ ] OKX 执行器
- [ ] 基础风控（仓位限额、价格异常）
- [ ] 仓位记录 + PnL 计算

### Phase 3（2 周）：自动化 + 监控完善
- [ ] 全自动执行
- [ ] Delta 再平衡
- [ ] 结算模块
- [ ] 一键清仓
- [ ] Grafana 监控面板

### Phase 4（持续）：优化
- [ ] IV 曲面构建
- [ ] 智能路由优化
- [ ] 回测框架
- [ ] 多策略并行

---

*本文档持续更新，当前为架构设计阶段草稿。*
