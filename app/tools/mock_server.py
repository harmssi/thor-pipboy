#!/usr/bin/env python3
"""Mock des ThorPipBoy-Plugins (gleiches Protokoll) zum Testen der App ohne Spiel.
Start: python3 -I tools/mock_server.py  ;  Gerät: adb reverse tcp:8767 tcp:8767
Fantasie-Daten, keine Spielinhalte."""
import asyncio, base64, hashlib, json, math, struct, time

PORT = 8767
state = {
    "radio_on": True,
    "inv": [
        {"id": "0x00000101", "stack": 0, "name": "Old Laser Rifle", "type": "weapon", "count": 1, "equipped": True, "weight": 12.6, "value": 57},
        {"id": "0x00000102", "stack": 0, "name": "Service Pistol", "type": "weapon", "count": 1, "equipped": False, "weight": 4.2, "value": 53},
        {"id": "0x00000103", "stack": 0, "name": "Baseball Bat", "type": "weapon", "count": 1, "equipped": False, "weight": 3.0, "value": 25},
        {"id": "0x00000201", "stack": 0, "name": "Blue Jumpsuit", "type": "apparel", "count": 1, "equipped": True, "weight": 1.0, "value": 10},
        {"id": "0x00000202", "stack": 0, "name": "Leather Vest", "type": "apparel", "count": 1, "equipped": False, "weight": 5.0, "value": 25},
        {"id": "0x00000301", "stack": 0, "name": "Med Kit", "type": "aid", "count": 7, "equipped": False, "weight": 0.1, "value": 50},
        {"id": "0x00000302", "stack": 0, "name": "Rad Flush", "type": "aid", "count": 3, "equipped": False, "weight": 0.1, "value": 80},
        {"id": "0x00000303", "stack": 0, "name": "Clean Water", "type": "aid", "count": 4, "equipped": False, "weight": 0.5, "value": 20},
        {"id": "0x00000401", "stack": 0, "name": "Energy Cell", "type": "ammo", "count": 112, "equipped": False, "weight": 0, "value": 2},
        {"id": "0x00000501", "stack": 0, "name": "Super Glue", "type": "junk", "count": 2, "equipped": False, "weight": 0.5, "value": 25},
        {"id": "0x00000601", "stack": 0, "name": "Holotape: Notes", "type": "holotape", "count": 1, "equipped": False, "weight": 0, "value": 0},
    ],
}

def collect(key):
    t = time.time()
    if key == "game": return {"ready": True, "player": True, "pipboyOpen": False}
    if key == "player.stats":
        return {"name": "Tobi", "level": 23, "xpProgress": (t / 60) % 1, "hp": 245, "hpMax": 310, "ap": 88, "apMax": 105,
                "rads": 65, "caps": 1834, "weight": 187.4, "weightMax": 260, "damageResist": 64, "energyResist": 42, "radResist": 15}
    if key == "player.special":
        v = dict(S=5, P=4, E=3, C=6, I=7, A=5, L=2)
        return [{"key": k, "value": n + (1 if k == "P" else 0), "base": n} for k, n in v.items()]
    if key == "player.limbs":
        return {k: {"value": 0 if k == "leftLeg" else 75, "max": 100, "crippled": k == "leftLeg"}
                for k in ["head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg"]}
    if key == "player.inventory": return state["inv"]
    if key == "player.quests":
        return [{"quest": "Lost and Found", "questId": "0x1", "objective": "Talk to the mechanic", "index": 10, "state": "active"},
                {"quest": "Lost and Found", "questId": "0x1", "objective": "Reach the town", "index": 5, "state": "completed"},
                {"quest": "Market Day", "questId": "0x2", "objective": "Find the market", "index": 20, "state": "active"}]
    if key == "player.location":
        return {"x": -3000 + 2000 * math.sin(t / 20), "y": 4000, "z": 120, "heading": t % 6.28, "interior": False,
                "cell": "Wilderness", "location": "Old Mill", "worldspace": "Wasteland", "worldspaceId": "0x0000003C"}
    if key == "world.time": return {"year": 2287, "month": 9, "day": 23, "hour": (t / 10) % 24, "daysPassed": 12}
    if key == "radio":
        return {"enabled": state["radio_on"], "frequency": 98.0,
                "stations": [{"text": "Town Radio", "active": True, "inRange": True},
                             {"text": "Classical Radio", "active": False, "inRange": True},
                             {"text": "Beacon", "active": False, "inRange": True}]}
    if key == "pipboy.Map":
        locs = [{"X": -60000 + (i * 7919) % 120000, "Y": -60000 + (i * 104729) % 120000, "text": f"Ort {i}",
                 "Discovered": i % 3 != 0, "Visible": True} for i in range(60)]
        return {"World": {"Extents": {"NWX": -90000, "NWY": 90000, "NEX": 90000, "NEY": 90000, "SWX": -90000, "SWY": -90000},
                          "Locations": locs, "Player": {"X": -3000 + 2000 * math.sin(t / 20), "Y": 4000, "Rotation": (t * 20) % 360}}}
    return None

def frame(text):
    b = text.encode()
    n = len(b)
    if n < 126: h = struct.pack("!BB", 0x81, n)
    elif n < 65536: h = struct.pack("!BBH", 0x81, 126, n)
    else: h = struct.pack("!BBQ", 0x81, 127, n)
    return h + b

async def handle(reader, writer):
    req = await reader.readuntil(b"\r\n\r\n")
    key = [l.split(b":", 1)[1].strip() for l in req.split(b"\r\n") if l.lower().startswith(b"sec-websocket-key")][0]
    acc = base64.b64encode(hashlib.sha1(key + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest())
    writer.write(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + b"\r\n\r\n")
    writer.write(frame(json.dumps({"type": "hello", "server": "ThorPipBoy-Mock", "protocol": 1, "ready": True, "keys": []})))
    subs = {}
    async def pusher():
        while True:
            now = time.time()
            for sid, s in subs.items():
                if now >= s["due"]:
                    s["due"] = now + s["freq"] / 1000
                    payload = json.dumps({"type": "data", "id": sid, "data": {k: collect(k) for k in s["fields"]}})
                    if payload != s.get("last"):
                        s["last"] = payload
                        writer.write(frame(payload))
            await writer.drain()
            await asyncio.sleep(0.05)
    task = asyncio.create_task(pusher())
    try:
        while True:
            b0, b1 = await reader.readexactly(2)
            n = b1 & 0x7F
            if n == 126: n = struct.unpack("!H", await reader.readexactly(2))[0]
            elif n == 127: n = struct.unpack("!Q", await reader.readexactly(8))[0]
            mask = await reader.readexactly(4) if b1 & 0x80 else b"\0\0\0\0"
            data = bytes(c ^ mask[i % 4] for i, c in enumerate(await reader.readexactly(n)))
            if b0 & 0x0F == 8: break
            m = json.loads(data)
            t = m.get("type")
            if t == "subscribe":
                f = m.get("fields", [])
                subs[m["id"]] = {"fields": f if isinstance(f, list) else list(f.values()),
                                 "freq": m.get("settings", {}).get("frequency", 500), "due": 0}
            elif t == "heartbeat":
                writer.write(frame(json.dumps({"type": "heartbeat", "ts": int(time.time() * 1000), "ready": True})))
            elif t == "command":
                p = m.get("params", {}); res = None
                if m["command"] == "radio":
                    state["radio_on"] = p.get("enable", not state["radio_on"]); res = {"enabled": state["radio_on"]}
                else:
                    for it in state["inv"]:
                        if it["id"] == p.get("id"):
                            if it["type"] == "aid":
                                it["count"] -= 1; res = {"action": "used"}
                            else:
                                if not it["equipped"] and it["type"] == "weapon":
                                    for o in state["inv"]:
                                        if o["type"] == "weapon": o["equipped"] = False
                                it["equipped"] = not it["equipped"]; res = {"action": "equipped" if it["equipped"] else "unequipped"}
                    state["inv"] = [i for i in state["inv"] if i["count"] > 0]
                writer.write(frame(json.dumps({"type": "commandResult", "id": m.get("id"), "command": m["command"],
                                               "success": res is not None, "result": res, **({} if res else {"error": "item_not_found"})})))
                for s in subs.values(): s["due"] = 0
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    finally:
        task.cancel(); writer.close()

async def main():
    srv = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"Mock läuft auf ws://127.0.0.1:{PORT}", flush=True)
    await srv.serve_forever()

asyncio.run(main())
