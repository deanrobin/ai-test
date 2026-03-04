# PRD: Polymarket × OKX 期权对冲套利系统

**版本：** v0.1  
**日期：** 2026-03-04  
**作者：** Dean（AI 辅助：派派）  
**状态：** 草稿

---

## 1. 背景与目标

### 1.1 背景

Polymarket 是基于 Polygon 链的去中心化预测市场，交易标的为二元结果合约（YES/NO Share，价格区间 $0–$1，最终结算为 $0 或 $1）。

OKX 提供加密货币期权交易，以 BTC/ETH 等资产为标的，具备标准化 Call/Put 合约、到期日和行权价体系。

**核心洞察：** 两个市场对同一宏观事件的定价往往存在偏差：
- Polymarket 反映的是市场对**事件发生概率**的预期
- OKX 期权的隐含波动率（IV）间接反映了**市场对价格方向的不确定性**

当两者对同一事件的定价出现显著偏差时，存在无方向性套利机会。

### 1.2 目标

| 目标 | 说明 |
|------|------|
| 对冲 | 利用 OKX 期权对 Polymarket 仓位进行 Delta 对冲，降低方向性风险 |
| 套利 | 捕捉两市场定价偏差，构造低风险正收益组合 |
| 自动化 | 系统化执行，减少人工干预 |

---

## 2. 市场结构分析

### 2.1 Polymarket 合约特性

```
合约类型：二元期权（Binary Option）
定价机制：CLOB（Central Limit Order Book）或 AMM
结算方式：事件发生 → $1，不发生 → $0
链上结算：Polygon 网络，UMA 协议裁决
流动性：较低，大单滑点明显
主要市场：加密价格、宏观事件、选举、监管
```

### 2.2 OKX 期权特性

```
合约类型：欧式期权（European Option）
定价模型：Black-Scholes，IV 驱动
结算方式：到期按标的价格结算
流动性：较高，做市商提供深度
品种：BTC、ETH、SOL 等主流资产
```

### 2.3 定价映射关系

Polymarket YES 价格可近似理解为市场对某事件的**风险中性概率**：

```
P_poly(YES) ≈ Q(event happens)
```

OKX 期权中，对于价格类事件（如"BTC > 100k"），其定价可通过 Black-Scholes 推导出隐含概率：

```
Q_okx(BTC > K at T) = N(d2)
  其中 d2 = [ln(S/K) + (r - σ²/2)T] / (σ√T)
```

**套利信号：**
```
Δ = P_poly(YES) - N(d2)
当 |Δ| > 阈值（如 5%）且流动性充足时，触发套利
```

---

## 3. 核心策略

### 策略 A：概率套利（Probability Arb）

**适用场景：** Polymarket 与 OKX 对同一价格事件的隐含概率存在偏差

**构建逻辑：**

| 情况 | Polymarket 操作 | OKX 操作 |
|------|----------------|----------|
| Poly 高估（P_poly > N_d2） | 卖出 YES / 买入 NO | 买入对应 Call（做多隐含概率） |
| Poly 低估（P_poly < N_d2） | 买入 YES | 卖出对应 Call（做空隐含概率） |

**盈利来源：** 两市场收敛时的价差

---

### 策略 B：Delta 对冲（方向性保护）

**适用场景：** 在 Polymarket 持有方向性仓位，通过 OKX 对冲标的资产价格波动风险

**构建步骤：**

```
1. 在 Polymarket 买入 BTC > 90k YES 仓位（名义规模 N USDC）
2. 估算该仓位的隐含 Delta：
   Delta_poly ≈ dP/dS ≈ N(d1) × N/S
3. 在 OKX 卖出对应 Delta 数量的 BTC（或买入 Put）
4. 持续 Delta 再平衡（Rebalancing）直到事件结算
```

**风险敞口：**
- Gamma 风险（价格剧烈波动时 Delta 漂移）
- 两市场流动性不匹配风险
- 结算时间差风险（Poly 基于事件，OKX 基于到期日）

---

### 策略 C：IV 偏差套利（波动率套利）

**适用场景：** Polymarket 隐含波动率 vs OKX 实际 IV 出现偏差

**计算方法：**

```python
# 从 Polymarket YES 价格反推隐含波动率
from scipy.stats import norm
from scipy.optimize import brentq
import numpy as np

def poly_implied_vol(price, S, K, T, r=0):
    """
    price: Polymarket YES 价格（0-1）
    S: 当前标的价格
    K: 行权价（事件触发价格）
    T: 到期时间（年化）
    """
    def objective(sigma):
        d2 = (np.log(S/K) + (r - 0.5*sigma**2)*T) / (sigma*np.sqrt(T))
        return norm.cdf(d2) - price
    
    try:
        return brentq(objective, 1e-6, 10)
    except:
        return None

# 对比 OKX 实际 IV
okx_iv = get_okx_iv(strike=K, expiry=T)  # 从 OKX API 获取
poly_iv = poly_implied_vol(price=0.65, S=95000, K=100000, T=30/365)

spread = okx_iv - poly_iv
if abs(spread) > 0.05:  # 5% 以上偏差
    trigger_arb(spread)
```

---

## 4. 系统架构

```
┌─────────────────────────────────────────┐
│              数据采集层                   │
│  Polymarket API  |  OKX WebSocket API   │
│  链上事件监听     |  期权链行情            │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│              信号计算层                   │
│  概率偏差计算  |  Delta 计算  |  IV 对比  │
│  套利信号生成  |  风险指标监控             │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│              风控层                       │
│  仓位限额  |  滑点保护  |  Gas 费估算     │
│  黑名单事件  |  异常价格过滤              │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│              执行层                       │
│  Polymarket：0x Protocol 链上交易        │
│  OKX：REST API 下单 / WebSocket 监控     │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│              监控 & 报告层                │
│  PnL 追踪  |  仓位看板  |  告警通知       │
└─────────────────────────────────────────┘
```

---

## 5. 关键风险

| 风险类型 | 描述 | 缓解措施 |
|---------|------|---------|
| 流动性风险 | Polymarket 深度不足，大单无法成交 | 限制单笔规模 < $5k |
| 模型风险 | Black-Scholes 假设不成立（肥尾） | 使用更保守的 IV 折扣 |
| 结算不一致 | Poly 事件结算时间 ≠ OKX 到期日 | 选择时间匹配的期权到期档 |
| Oracle 风险 | UMA 裁决争议导致 Poly 结算延迟 | 仅做非争议性高流动性市场 |
| 链上风险 | Gas 费飙升、Polygon 拥堵 | 预估 Gas，设置最大可接受费率 |
| 监管风险 | 部分地区限制预测市场使用 | 合规审查，地区过滤 |

---

## 6. 技术实现栈

| 模块 | 技术选型 |
|------|---------|
| 数据采集 | Python + asyncio，WebSocket 长连接 |
| 链上交互 | web3.py（Polygon），0x API |
| OKX 交互 | OKX Python SDK / REST + WS |
| 数学计算 | scipy, numpy, QuantLib |
| 数据存储 | TimescaleDB（时序行情）+ PostgreSQL（仓位） |
| 监控告警 | Grafana + Telegram Bot 通知 |
| 部署 | Docker Compose，云服务器（低延迟） |

---

## 7. MVP 范围（Phase 1）

**目标：** 在 30 天内实现最小可验证版本

- [ ] Polymarket API 接入，实时获取目标市场 YES/NO 价格
- [ ] OKX 期权行情接入，获取 IV、Delta、到期日数据
- [ ] 概率偏差计算模块（策略 A 信号生成）
- [ ] 手动执行界面（先不做自动化，人工确认下单）
- [ ] 基础 PnL 记录

**排除（Phase 2+）：**
- 全自动执行
- Delta 再平衡自动化
- 多品种并行

---

## 8. 成功指标

| 指标 | 目标值 |
|------|-------|
| 套利胜率 | > 60% |
| 平均单笔收益 | > 2% |
| 最大回撤 | < 15% |
| 月化收益率 | > 8% |
| 系统可用性 | > 99% |

---

## 附录：参考资料

- [Polymarket API Docs](https://docs.polymarket.com)
- [OKX Options API](https://www.okx.com/docs-v5/en/#trading-account-rest-api-get-options-market-data)
- [Prediction Markets as Options - Academic Paper](https://papers.ssrn.com)
- [0x Protocol Docs](https://docs.0x.org)

---

*本文档由派派 AI 辅助生成，仅供研究参考，不构成投资建议。*
