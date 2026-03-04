"""
数据观测层 - OKX 期权行情采集器

接口：
  - WebSocket Public: wss://ws.okx.com:8443/ws/v5/public
  - REST:             https://www.okx.com/api/v5

数据流：
  1. REST 拉取期权合约列表（instruments）
  2. WebSocket 订阅：
     - tickers（全档行情 + Greeks）
     - mark-price（标记价格）
  3. REST 轮询补充 Greeks（WebSocket 不含完整 Greeks 时）
"""

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Callable, Optional

import aiohttp
import websockets
from websockets.exceptions import ConnectionClosed

from .models import MarketDataEvent, OKXOptionTicker, OKXSpotTicker, OptionType
from .cache import MarketDataCache

logger = logging.getLogger(__name__)

OKX_WS_PUBLIC  = "wss://ws.okx.com:8443/ws/v5/public"
OKX_REST_BASE  = "https://www.okx.com/api/v5"

# 追踪的标的资产
UNDERLYINGS = ["BTC", "ETH", "SOL"]


class OKXCollector:

    def __init__(
        self,
        cache: MarketDataCache,
        event_callback: Optional[Callable[[MarketDataEvent], None]] = None,
        underlyings: Optional[list[str]] = None,
        max_dte: int = 60,        # 只关注 60 天内到期的期权
        min_oi: float = 0,        # 最小未平仓量过滤
    ):
        self.cache          = cache
        self.event_callback = event_callback
        self.underlyings    = underlyings or UNDERLYINGS
        self.max_dte        = max_dte
        self.min_oi         = min_oi

        self._session:     Optional[aiohttp.ClientSession] = None
        self._ws:          Optional[websockets.WebSocketClientProtocol] = None
        self._instruments: dict[str, dict] = {}   # instrument_id → instrument info
        self._running:     bool = False

    # ─── 生命周期 ─────────────────────────────────────────────────

    async def start(self):
        self._running = True
        self._session = aiohttp.ClientSession()
        logger.info("OKX 采集器启动")

        await asyncio.gather(
            self._instrument_refresh_loop(),
            self._ws_loop(),
            self._greeks_poll_loop(),
        )

    async def stop(self):
        self._running = False
        if self._ws:
            await self._ws.close()
        if self._session:
            await self._session.close()
        logger.info("OKX 采集器已停止")

    # ─── REST：期权合约列表 ────────────────────────────────────────

    async def _instrument_refresh_loop(self):
        """每 10 分钟刷新一次期权合约列表"""
        while self._running:
            try:
                for udl in self.underlyings:
                    instruments = await self._fetch_option_instruments(udl)
                    for inst in instruments:
                        iid = inst["instId"]
                        self._instruments[iid] = inst

                logger.info(f"OKX 期权合约数: {len(self._instruments)}")

                # 重新订阅
                if self._ws and self._instruments:
                    await self._subscribe_tickers()

            except Exception as e:
                logger.error(f"OKX 合约列表刷新失败: {e}")

            await asyncio.sleep(600)

    async def _fetch_option_instruments(self, underlying: str) -> list[dict]:
        """拉取指定标的的期权合约列表，过滤到期日"""
        async with self._session.get(
            f"{OKX_REST_BASE}/public/instruments",
            params={"instType": "OPTION", "uly": f"{underlying}-USD"},
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            resp.raise_for_status()
            data = await resp.json()

        instruments = []
        now = datetime.utcnow()

        for inst in data.get("data", []):
            # 解析到期时间（OKX 返回毫秒时间戳）
            expiry_ts = int(inst.get("expTime", 0)) / 1000
            expiry = datetime.utcfromtimestamp(expiry_ts)
            dte = (expiry - now).days

            if 0 < dte <= self.max_dte:
                instruments.append(inst)

        return instruments

    # ─── WebSocket：实时行情 ───────────────────────────────────────

    async def _ws_loop(self):
        retry_delay = 1

        while self._running:
            try:
                logger.info(f"OKX WS 连接中: {OKX_WS_PUBLIC}")
                async with websockets.connect(
                    OKX_WS_PUBLIC,
                    ping_interval=20,
                    ping_timeout=10,
                ) as ws:
                    self._ws = ws
                    retry_delay = 1

                    # 订阅现货价格
                    await self._subscribe_spots(ws)

                    # 订阅期权 tickers
                    if self._instruments:
                        await self._subscribe_tickers(ws)

                    await self._receive_loop(ws)

            except ConnectionClosed as e:
                logger.warning(f"OKX WS 断开: {e}, {retry_delay}s 后重连")
            except Exception as e:
                logger.error(f"OKX WS 异常: {e}")
            finally:
                self._ws = None

            if self._running:
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 2, 60)

    async def _subscribe_spots(self, ws=None):
        ws = ws or self._ws
        if not ws:
            return
        args = [{"channel": "tickers", "instId": f"{u}-USDT"} for u in self.underlyings]
        await ws.send(json.dumps({"op": "subscribe", "args": args}))
        logger.info(f"OKX 现货订阅: {[a['instId'] for a in args]}")

    async def _subscribe_tickers(self, ws=None):
        ws = ws or self._ws
        if not ws:
            return

        # OKX WS 单次订阅上限约 300 个 channel，超出需分批
        iids = list(self._instruments.keys())
        batch_size = 200

        for i in range(0, len(iids), batch_size):
            batch = iids[i:i + batch_size]
            args = [{"channel": "opt-summary", "uly": iid.split("-")[0] + "-USD"}
                    for iid in batch[:1]]  # opt-summary 按标的订阅

            # 改用 opt-summary channel（含 Greeks + IV）
            args_summary = []
            for udl in self.underlyings:
                args_summary.append({"channel": "opt-summary", "uly": f"{udl}-USD"})

            await ws.send(json.dumps({"op": "subscribe", "args": args_summary}))

        logger.info(f"OKX 期权 opt-summary 订阅完成")

    async def _receive_loop(self, ws):
        async for raw in ws:
            if not self._running:
                break
            try:
                msg = json.loads(raw)
                await self._handle_message(msg)
            except Exception as e:
                logger.error(f"OKX 消息处理异常: {e}")

    async def _handle_message(self, msg: dict):
        if "event" in msg:
            if msg["event"] == "error":
                logger.error(f"OKX WS 订阅错误: {msg}")
            return

        channel = msg.get("arg", {}).get("channel", "")
        data    = msg.get("data", [])

        if channel == "opt-summary":
            for item in data:
                await self._on_opt_summary(item)
        elif channel == "tickers":
            for item in data:
                await self._on_spot_ticker(item)

    async def _on_opt_summary(self, item: dict):
        """处理期权摘要（含 Greeks + IV）"""
        iid = item.get("instId", "")
        inst_info = self._instruments.get(iid)

        # 解析合约信息
        parts = iid.split("-")
        if len(parts) < 4:
            return

        underlying  = parts[0]
        expiry_str  = parts[2]   # YYYYMMDD
        strike      = float(parts[3])
        opt_type    = OptionType.CALL if parts[4] == "C" else OptionType.PUT

        expiry = datetime.strptime(expiry_str, "%Y%m%d").replace(
            hour=8, tzinfo=timezone.utc  # OKX 期权 08:00 UTC 到期
        )

        # 现货价格
        spot_symbol = f"{underlying}-USDT"
        spot = self.cache.get_okx_spot(spot_symbol)
        spot_price = float(item.get("fwdPx", spot.price if spot else 0))

        bid_price = float(item.get("bidPx", 0) or 0)
        ask_price = float(item.get("askPx", 0) or 0)

        ticker = OKXOptionTicker(
            instrument_id = iid,
            underlying    = underlying,
            strike        = strike,
            expiry        = expiry,
            option_type   = opt_type,
            best_bid      = bid_price,
            best_ask      = ask_price,
            last_price    = float(item.get("last", 0) or 0),
            mid_price     = (bid_price + ask_price) / 2 if bid_price and ask_price else 0,
            iv            = float(item.get("vol", 0) or 0) / 100,  # 转换为小数
            mark_price    = float(item.get("markVol", 0) or 0),
            delta         = float(item.get("delta", 0) or 0),
            gamma         = float(item.get("gamma", 0) or 0),
            vega          = float(item.get("vega", 0) or 0),
            theta         = float(item.get("theta", 0) or 0),
            spot_price    = spot_price,
            open_interest = float(item.get("oi", 0) or 0),
            volume_24h    = float(item.get("volCcy24h", 0) or 0),
            ts            = datetime.utcnow(),
        )

        await self.cache.set_okx_option(ticker)
        self._emit(MarketDataEvent(source="OKX_OPTION", data=ticker))

    async def _on_spot_ticker(self, item: dict):
        """处理现货行情"""
        symbol = item.get("instId", "")
        if not symbol:
            return

        ticker = OKXSpotTicker(
            symbol     = symbol,
            price      = float(item.get("last", 0) or 0),
            change_24h = float(item.get("change24h", 0) or 0),
            volume_24h = float(item.get("vol24h", 0) or 0),
            ts         = datetime.utcnow(),
        )

        await self.cache.set_okx_spot(ticker)
        self._emit(MarketDataEvent(source="OKX_SPOT", data=ticker))

    # ─── REST：Greeks 补充轮询 ────────────────────────────────────

    async def _greeks_poll_loop(self):
        """
        每 10s 用 REST 补充 Greeks，
        防止 WS 推送不及时的情况
        """
        # 等待 WS 先建连
        await asyncio.sleep(15)

        while self._running:
            try:
                for udl in self.underlyings:
                    await self._poll_greeks(udl)
            except Exception as e:
                logger.error(f"Greeks 轮询失败: {e}")

            await asyncio.sleep(10)

    async def _poll_greeks(self, underlying: str):
        async with self._session.get(
            f"{OKX_REST_BASE}/public/opt-summary",
            params={"uly": f"{underlying}-USD"},
            timeout=aiohttp.ClientTimeout(total=8),
        ) as resp:
            if resp.status != 200:
                return
            data = await resp.json()

        for item in data.get("data", []):
            await self._on_opt_summary(item)

    # ─── 工具 ────────────────────────────────────────────────────

    def _emit(self, event: MarketDataEvent):
        if self.event_callback:
            try:
                self.event_callback(event)
            except Exception as e:
                logger.error(f"event_callback 异常: {e}")

    async def get_option_chain(self, underlying: str, expiry_date: str) -> list[OKXOptionTicker]:
        """
        获取指定标的和到期日的完整期权链
        expiry_date: 格式 "YYYYMMDD"
        """
        result = []
        for iid, ticker in self.cache._okx_options.items():
            if (iid.startswith(underlying) and
                    ticker.expiry.strftime("%Y%m%d") == expiry_date):
                result.append(ticker)
        return sorted(result, key=lambda t: t.strike)
