"""
统一配置加载模块
支持 YAML 文件 + 环境变量覆盖
"""

import os
import yaml
from pathlib import Path


def load_config(path: str = "config.yaml") -> dict:
    cfg = {}

    # 从 YAML 文件加载
    cfg_path = Path(path)
    if cfg_path.exists():
        with open(cfg_path) as f:
            cfg = yaml.safe_load(f) or {}

    # 环境变量覆盖
    overrides = {
        "redis_url":        os.getenv("REDIS_URL"),
        "polygon_ws_url":   os.getenv("POLYGON_WS_URL"),
        "polygon_rpc_url":  os.getenv("POLYGON_RPC_URL"),
        "okx_api_key":      os.getenv("OKX_API_KEY"),
        "okx_secret":       os.getenv("OKX_SECRET"),
        "okx_passphrase":   os.getenv("OKX_PASSPHRASE"),
        "telegram_token":   os.getenv("TELEGRAM_BOT_TOKEN"),
        "telegram_chat_id": os.getenv("TELEGRAM_CHAT_ID"),
    }

    for key, val in overrides.items():
        if val is not None:
            cfg[key] = val

    return cfg


# 默认配置（无配置文件时使用）
DEFAULT_CONFIG = {
    "redis_url":          "redis://localhost:6379/0",

    # Polymarket
    "poly_max_markets":   50,
    "poly_keyword_filter": ["bitcoin", "btc", "ethereum", "eth", "price", "above", "below"],

    # OKX
    "okx_underlyings":   ["BTC", "ETH"],
    "okx_max_dte":       60,

    # 策略
    "arb_spread_threshold":    0.05,
    "min_liquidity_usd":       1000,
    "hedge_rebalance_threshold": 0.10,

    # 风控
    "max_single_position_usd": 5000,
    "max_total_exposure_usd":  50000,
    "max_drawdown_pct":        0.15,
    "daily_loss_limit_usd":    3000,
    "stop_loss_pct":           0.10,
    "take_profit_pct":         0.05,
}
