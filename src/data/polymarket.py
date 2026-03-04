"""
数据观测层 - Polymarket 行情采集器

接口：
  - REST:      https://clob.polymarket.com  （市场元数据、历史）
  - WebSocket: wss://ws-subscriptions-clob.polymarket.com/ws/market  （实时行情）

数据流：
  1. 启动时 REST 拉取活跃市场列表
  2. WebSocket 订阅所有活跃市场的 price_change 事件
  3. 标准化后写入缓存，并推送到 event_bus
"""

import asyncio
import json
import logging
from datetime import datetime
from typing import AsyncIterator, Callable, Optional

import aiohttp
import websockets
from websockets.exceptions import ConnectionClosed

from .models import MarketDataEvent, MarketStatus, PolyTicker
from .cache import MarketDataCache

logger = logging.getLogger(__name__)

CLOB_REST_URL = "https://clob.polymarket.com"
CLOB_WS_URL   = "wss://ws-subscriptions-clob.polymarket.com/ws/market"

# 目标市场关键词过滤（只关注加密价格类市场，可配置）
CRYPTO_KEYWORDS = ["bitcoin", "btc", "ethereum", "eth", "crypto", "price", "above", "below"]


class PolymarketCollector:

    def __init__(
        self,
        cache: MarketDataCache,
        event_callback: Optional[Callable[[MarketDataEvent], None]] = None,
        market_filter: Optional[list[str]] = None,   # 指定 market_id 白名单
        keyword_filter: Optional[list[str]] = None,  # 关键词过滤
        max_markets: int = 50,
    ):
        self.cache          = cache
        self.event_callback = event_callback
        self.market_filter  = set(market_filter or [])
        self.keyword_filter = [k.lower() for k in (keyword_filter or CRYPTO_KEYWORDS)]
        self.max_markets    = max_markets

        self._session:        Optional[aiohttp.ClientSession] = None
        self._ws:             Optional[websockets.WebSocketClientProtocol] = None
        self._subscribed_ids: set[str] = set()
        self._running:        bool = False

    # ─── 生命周期 ─────────────────────────────────────────────────

    async def start(self):
        self._running = True
        self._session = aiohttp.ClientSession()
        logger.info("Polymarket 采集器启动")

        # 并发运行：REST 轮询 + WebSocket 行情
        await asyncio.gather(
            self._market_refresh_loop(),
            self._ws_loop(),
        )

    async def stop(self):
        self._running = False
        if self._ws:
            await self._ws.close()
        if self._session:
            await self._session.close()
        logger.info("Polymarket 采集器已停止")

    # ─── REST：市场列表 ───────────────────────────────────────────

    async def _market_refresh_loop(self):
        """每 5 分钟刷新一次活跃市场列表"""
        while self._running:
            try:
                markets = await self._fetch_active_markets()
                logger.info(f"Polymarket 活跃市场数: {len(markets)}")

                # 新市场加入 WebSocket 订阅
                new_ids = {m["condition_id"] for m in markets} - self._subscribed_ids
                if new_ids and self._ws:
                    await self._subscribe(list(new_ids))

            except Exception as e:
                logger.error(f"市场列表刷新失败: {e}")

            await asyncio.sleep(300)

    async def _fetch_active_markets(self) -> list[dict]:
        """拉取 CLOB 活跃市场，按关键词/白名单过滤"""
        markets = []
        next_cursor = ""

        while True:
            params = {"active": "true", "closed": "false", "limit": 100}
            if next_cursor:
                params["next_cursor"] = next_cursor

            async with self._session.get(
                f"{CLOB_REST_URL}/markets", params=params, timeout=aiohttp.ClientTimeout(total=10)
            ) as resp:
                resp.raise_for_status()
                data = await resp.json()

            for m in data.get("data", []):
                if self._should_track(m):
                    markets.append(m)

            next_cursor = data.get("next_cursor", "")
            if not next_cursor or len(markets) >= self.max_markets:
                break

        return markets[:self.max_markets]

    def _should_track(self, market: dict) -> bool:
        """判断是否追踪该市场"""
        # 白名单优先
        if self.market_filter:
            return market.get("condition_id") in self.market_filter

        # 关键词过滤
        title = (market.get("question", "") + " " + market.get("description", "")).lower()
        return any(kw in title for kw in self.keyword_filter)

    # ─── WebSocket：实时行情 ──────────────────────────────────────

    async def _ws_loop(self):
        """WebSocket 主循环，自动重连"""
        retry_delay = 1

        while self._running:
            try:
                logger.info(f"Polymarket WS 连接中: {CLOB_WS_URL}")
                async with websockets.connect(
                    CLOB_WS_URL,
                    ping_interval=20,
                    ping_timeout=10,
                ) as ws:
                    self._ws = ws
                    retry_delay = 1   # 连接成功，重置重试延迟

                    # 订阅已知市场
                    if self._subscribed_ids:
                        await self._subscribe(list(self._subscribed_ids))

                    await self._receive_loop(ws)

            except ConnectionClosed as e:
                logger.warning(f"Polymarket WS 断开: {e}, {retry_delay}s 后重连")
            except Exception as e:
                logger.error(f"Polymarket WS 异常: {e}, {retry_delay}s 后重连")
            finally:
                self._ws = None

            if self._running:
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 2, 60)

    async def _subscribe(self, condition_ids: list[str]):
        """订阅指定市场的行情"""
        if not self._ws:
            return

        msg = {
            "type": "subscribe",
            "channel": "live_activity",
            "markets": condition_ids,
        }
        await self._ws.send(json.dumps(msg))
        self._subscribed_ids.update(condition_ids)
        logger.info(f"Polymarket WS 订阅 {len(condition_ids)} 个市场")

    async def _receive_loop(self, ws):
        """处理 WebSocket 消息"""
        async for raw in ws:
            if not self._running:
                break
            try:
                await self._handle_message(raw)
            except Exception as e:
                logger.error(f"消息处理异常: {e} | raw={raw[:200]}")

    async def _handle_message(self, raw: str):
        msgs = json.loads(raw)
        if not isinstance(msgs, list):
            msgs = [msgs]

        for msg in msgs:
            event_type = msg.get("event_type") or msg.get("type", "")

            if event_type == "price_change":
                await self._on_price_change(msg)
            elif event_type == "book":
                await self._on_orderbook(msg)
            elif event_type == "tick_size_change":
                pass  # 忽略
            else:
                logger.debug(f"未知 Poly 事件: {event_type}")

    async def _on_price_change(self, msg: dict):
        """处理价格变更事件，构建 PolyTicker"""
        market_id    = msg.get("asset_id", "")
        condition_id = msg.get("condition_id", market_id)

        for change in msg.get("changes", [msg]):
            outcome   = "YES" if change.get("outcome", "yes").upper() == "YES" else "NO"
            price     = float(change.get("price", 0))
            side      = change.get("side", "")   # BUY = bid, SELL = ask

            # 从缓存取已有数据做增量更新
            existing = self.cache.get_poly_ticker(condition_id, outcome)
            best_bid = existing.best_bid if existing else 0.0
            best_ask = existing.best_ask if existing else 1.0

            if side == "BUY":
                best_bid = price
            elif side == "SELL":
                best_ask = price

            ticker = PolyTicker(
                market_id    = condition_id,
                condition_id = condition_id,
                event_title  = msg.get("market", ""),
                outcome      = outcome,
                best_bid     = best_bid,
                best_ask     = best_ask,
                last_price   = price,
                mid_price    = (best_bid + best_ask) / 2,
                spread       = best_ask - best_bid,
                volume_24h   = float(msg.get("volume", 0)),
                liquidity    = float(msg.get("liquidity", 0)),
                expire_time  = self._parse_time(msg.get("end_date_iso")),
                status       = MarketStatus.ACTIVE,
                ts           = datetime.utcnow(),
            )

            await self.cache.set_poly_ticker(ticker)
            self._emit(MarketDataEvent(source="POLYMARKET", data=ticker))

    async def _on_orderbook(self, msg: dict):
        """处理订单簿快照（用于流动性计算）"""
        # 可选：更新 PolyOrderBook 到缓存
        pass

    # ─── 工具 ─────────────────────────────────────────────────────

    def _emit(self, event: MarketDataEvent):
        if self.event_callback:
            try:
                self.event_callback(event)
            except Exception as e:
                logger.error(f"event_callback 异常: {e}")

    @staticmethod
    def _parse_time(s: Optional[str]) -> Optional[datetime]:
        if not s:
            return None
        try:
            return datetime.fromisoformat(s.replace("Z", "+00:00"))
        except Exception:
            return None

    # ─── 单次查询（工具方法）─────────────────────────────────────

    async def fetch_market(self, condition_id: str) -> Optional[dict]:
        """查询单个市场详情"""
        async with self._session.get(
            f"{CLOB_REST_URL}/markets/{condition_id}",
            timeout=aiohttp.ClientTimeout(total=5)
        ) as resp:
            if resp.status == 200:
                return await resp.json()
            return None

    async def fetch_orderbook(self, token_id: str) -> Optional[dict]:
        """查询订单簿快照"""
        async with self._session.get(
            f"{CLOB_REST_URL}/book",
            params={"token_id": token_id},
            timeout=aiohttp.ClientTimeout(total=5)
        ) as resp:
            if resp.status == 200:
                return await resp.json()
            return None
