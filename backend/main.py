from fastapi import FastAPI, Query
from pydantic import BaseModel
import httpx
from typing import Optional, Dict, Any, List
from datetime import datetime, timezone

app = FastAPI(title="Env Monitor API (v2)",
              description="Aggrega Open-Meteo Weather, Air Quality e Flood (sem API key).",
              version="1.1.0")

OPEN_METEO_FORECAST = "https://api.open-meteo.com/v1/forecast"
OPEN_METEO_AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
OPEN_METEO_FLOOD = "https://flood-api.open-meteo.com/v1/flood"
OPEN_METEO_GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

async def _safe_get_json(client, url: str, params: dict) -> dict:
    try:
        r = await client.get(url, params=params)
        r.raise_for_status()
        return {"ok": True, "data": r.json(), "error": None}
    except Exception as e:
        return {"ok": False, "data": None, "error": str(e)}

class EnvironmentSummary(BaseModel):
    location: Dict[str, float]
    current: Dict[str, Any]
    next_hours: Dict[str, Any]
    next_days: Dict[str, Any]
    risk: Dict[str, str]
    sources: List[str]

@app.get("/status")
def status():
    return {"ok": True, "service": "env-monitor-api-v2", "utc": datetime.now(timezone.utc).isoformat()}

@app.get("/places")
async def places(q: str = Query(..., min_length=2)):
    params = {"name": q, "count": 10, "language": "pt", "format": "json"}
    async with httpx.AsyncClient(timeout=15) as client:
        res = await _safe_get_json(client, OPEN_METEO_GEOCODE, params)
    data = (res["data"] or {})
    items = []
    for it in data.get("results", []) or []:
        items.append({
            "name": it.get("name"),
            "country": it.get("country"),
            "lat": it.get("latitude"),
            "lon": it.get("longitude"),
            "admin1": it.get("admin1"),
        })
    return {"query": q, "results": items, "error": res["error"]}

def _get_current_index(times: List[str]) -> int:
    if not times:
        return 0
    now = datetime.utcnow().replace(minute=0, second=0, microsecond=0).isoformat() + "Z"
    try:
        return max(0, min(len(times)-1, times.index(now)))
    except ValueError:
        # Fallback: achar o mais próximo no relógio, comparando tudo como "naive" (sem tz)
        def to_dt_naive(s):
            try:
                if s.endswith("Z"):
                    # Converte para UTC e remove tz para ficar naive
                    dt_aware = datetime.fromisoformat(s.replace("Z", "+00:00"))
                    return dt_aware.replace(tzinfo=None)
                # Sem "Z": já é local naive
                return datetime.fromisoformat(s)
            except Exception:
                return None

        dt_now = datetime.utcnow().replace(minute=0, second=0, microsecond=0)  # naive em UTC
        diffs = []
        for i, s in enumerate(times):
            dt = to_dt_naive(s)
            if dt is None:
                diffs.append((i, 1e18))
            else:
                try:
                    diffs.append((i, abs((dt - dt_now).total_seconds())))
                except Exception:
                    diffs.append((i, 1e18))
        diffs.sort(key=lambda x: x[1])
        return diffs[0][0] if diffs else 0

@app.get("/raw/openmeteo")
async def raw_openmeteo(lat: float, lon: float):
    async with httpx.AsyncClient(timeout=20) as client:
        w = await _safe_get_json(client, OPEN_METEO_FORECAST, {
            "latitude": lat, "longitude": lon,
            "hourly": "temperature_2m,precipitation,precipitation_probability,rain,weather_code",
            "forecast_days": 3, "timezone": "UTC"
        })
        a = await _safe_get_json(client, OPEN_METEO_AIR, {
            "latitude": lat, "longitude": lon,
            "hourly": "pm2_5,pm10,ozone,nitrogen_dioxide,european_aqi,us_aqi",
            "timezone": "UTC"
        })
        f = await _safe_get_json(client, OPEN_METEO_FLOOD, {
            "latitude": lat, "longitude": lon,
            "daily": "river_discharge",
            "forecast_days": 7,
            "timezone": "auto"
        })
    return {"weather": w, "air": a, "flood": f}

def _aqi_label(us_aqi: Optional[float]) -> str:
    if us_aqi is None:
        return "desconhecido"
    try:
        v = float(us_aqi)
    except Exception:
        return "desconhecido"
    if v <= 50: return "bom"
    if v <= 100: return "moderado"
    if v <= 150: return "ruim p/ sensíveis"
    if v <= 200: return "ruim"
    if v <= 300: return "muito ruim"
    return "perigoso"

@app.get("/environment", response_model=EnvironmentSummary)
async def environment(lat: float, lon: float):
    async with httpx.AsyncClient(timeout=20) as client:
        w = await _safe_get_json(client, OPEN_METEO_FORECAST, {
            "latitude": lat, "longitude": lon,
            "hourly": "temperature_2m,precipitation,precipitation_probability,rain,weather_code",
            "forecast_days": 2, "timezone": "auto"
        })
        a = await _safe_get_json(client, OPEN_METEO_AIR, {
            "latitude": lat, "longitude": lon,
            "hourly": "pm2_5,pm10,ozone,nitrogen_dioxide,european_aqi,us_aqi",
            "timezone": "auto"
        })
        f = await _safe_get_json(client, OPEN_METEO_FLOOD, {
            "latitude": lat, "longitude": lon,
            "daily": "river_discharge", "forecast_days": 7, "timezone": "auto"
        })

    weather = (w["data"] if w["ok"] else {}) or {}
    air = (a["data"] if a["ok"] else {}) or {}
    flood = (f["data"] if f["ok"] else {}) or {}

    w_hourly = weather.get("hourly", {})
    a_hourly = air.get("hourly", {})
    w_times = w_hourly.get("time", []) or []
    a_times = a_hourly.get("time", []) or []
    wi = _get_current_index(w_times) if w_times else 0
    ai = _get_current_index(a_times) if a_times else 0

    def safe_get(seq, idx):
        try: return seq[idx]
        except Exception: return None

    current = {
        "temperature_c": safe_get(w_hourly.get("temperature_2m") or [], wi) if w_times else None,
        "precipitation_mm": safe_get(w_hourly.get("precipitation") or [], wi) if w_times else None,
        "precipitation_probability_pct": safe_get(w_hourly.get("precipitation_probability") or [], wi) if w_times else None,
        "pm2_5": safe_get(a_hourly.get("pm2_5") or [], ai) if a_times else None,
        "pm10": safe_get(a_hourly.get("pm10") or [], ai) if a_times else None,
        "us_aqi": safe_get(a_hourly.get("us_aqi") or [], ai) if a_times else None,
        "european_aqi": safe_get(a_hourly.get("european_aqi") or [], ai) if a_times else None,
    }

    nh_precip_prob = None
    nh_precip_sum = None
    if w_times and w_hourly.get("precipitation_probability"):
        sl = (w_hourly.get("precipitation_probability") or [])[wi:wi+6]
        nh_precip_prob = max([v for v in sl if v is not None], default=None)
    if w_times and w_hourly.get("precipitation"):
        nh_precip_sum = sum([v for v in (w_hourly.get("precipitation") or [])[wi:wi+6] if v is not None])

    next_hours = {
        "max_precipitation_probability_next6h_pct": nh_precip_prob,
        "total_precipitation_next6h_mm": nh_precip_sum,
        "avg_pm2_5_next6h": None
    }
    if a_times and a_hourly.get("pm2_5"):
        sl = [v for v in (a_hourly.get("pm2_5") or [])[ai:ai+6] if v is not None]
        next_hours["avg_pm2_5_next6h"] = round(sum(sl)/len(sl), 1) if sl else None

    f_daily = flood.get("daily", {}) if flood else {}
    river_times = f_daily.get("time", []) or []
    river_vals = f_daily.get("river_discharge", []) or []
    next_days = {"river_discharge_m3s_next7d": {"time": river_times[:7], "values": river_vals[:7]}}

    pprob = nh_precip_prob or 0
    rain_risk = "alto" if pprob >= 70 else ("médio" if pprob >= 40 else "baixo")
    aqi_label = _aqi_label(current.get("us_aqi"))
    flood_risk = "desconhecido"
    if river_vals:
        flood_risk = "baixo"
        if len(river_vals) >= 3:
            base = river_vals[0] or 0.0
            ahead = max(river_vals[1:4]) if len(river_vals) >= 4 else (river_vals[-1] or 0.0)
            try:
                inc = (ahead - base) / (base + 1e-6)
            except Exception:
                inc = 0.0
            if inc >= 0.5: flood_risk = "alto"
            elif inc >= 0.2: flood_risk = "médio"

    risk = {"chuva": rain_risk, "qualidade_do_ar": aqi_label, "alagamento": flood_risk}

    return EnvironmentSummary(
        location={"lat": lat, "lon": lon},
        current=current, next_hours=next_hours, next_days=next_days, risk=risk,
        sources=[
            "Open-Meteo Weather Forecast API" + ("" if w["ok"] else f" (falhou: {w['error']})"),
            "Open-Meteo Air Quality API" + ("" if a["ok"] else f" (falhou: {a['error']})"),
            "Open-Meteo Global Flood API" + ("" if f["ok"] else f" (falhou: {f['error']})"),
        ]
    )
