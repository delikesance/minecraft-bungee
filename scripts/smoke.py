#!/usr/bin/env python3
"""Exercise real Docker control without starting Minecraft or accepting its EULA."""
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent

def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, text=True, capture_output=True, **kwargs).stdout.strip()

def main():
    stack = "vampyuyu-smoke-" + uuid.uuid4().hex[:8]
    temp = Path(tempfile.mkdtemp(prefix=stack + "-"))
    containers = []
    keep = os.environ.get("SMOKE_KEEP") == "1"
    password = os.environ.get("SMOKE_PASSWORD") or secrets.token_urlsafe(24)
    origin = ""
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def api(route, method="GET", data=None, csrf="", expected=200):
        req = urllib.request.Request(origin + "/api/v1" + route,
            data=json.dumps(data).encode() if data is not None else (b"" if method == "POST" else None),
            method=method, headers={"Origin":origin,"Content-Type":"application/json","X-CSRF-Token":csrf})
        try:
            response = opener.open(req, timeout=75)
        except urllib.error.HTTPError as error:
            response = error
        assert response.code == expected, (route, response.code, response.read().decode())
        return json.load(response)
    try:
        (temp/"panel").mkdir()
        (temp/"secrets").mkdir(mode=0o700)
        for name in ["controller_token","role_bridge_token","lobby_rcon","creative_rcon","survival_rcon"]:
            filename=temp/"secrets"/name
            filename.write_text(secrets.token_hex(32))
            filename.chmod(0o600)
        token=(temp/"secrets"/"controller_token").read_text()
        docker("network","create",stack)
        name=stack+"-creative"; containers.append(name)
        docker("run","-d","--init","--name",name,"--network",stack,"--network-alias","creative",
               "--label","win.vampyuyu.stack="+stack,"--label","win.vampyuyu.server=creative",
               "--mount",f"type=bind,source={temp/'secrets'/'controller_token'},target=/token,readonly",
               "alpine:3.22","sh","-c",'echo "<script>window.injected=true</script>"; cat /token; echo; exec sleep 3600')
        name=stack+"-controller"; containers.append(name)
        docker("run","-d","--name",name,"--network",stack,"--network-alias","controller",
               "--group-add",str(os.stat("/var/run/docker.sock").st_gid),
               "--mount","type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
               "--mount",f"type=bind,source={temp/'secrets'},target=/run/secrets,readonly",
               "-e","STACK_ID="+stack,"minecraft-bungee-controller:test")
        docker("run","--rm","-i","--mount",f"type=bind,source={temp/'panel'},target=/state",
               "-e","DB_PATH=/state/panel.db","minecraft-bungee-panel:test","admin","admin",input=password)
        # Reserve a port before creating the container; Docker still handles the bind atomically.
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0)); port=sock.getsockname()[1]
        origin=f"http://127.0.0.1:{port}"
        name=stack+"-panel"; containers.append(name)
        docker("run","-d","--name",name,"--network",stack,"-p",f"127.0.0.1:{port}:8080",
               "--mount",f"type=bind,source={temp/'panel'},target=/state",
               "--mount",f"type=bind,source={temp/'secrets'/'controller_token'},target=/run/secrets/controller_token,readonly",
               "--mount",f"type=bind,source={temp/'secrets'/'role_bridge_token'},target=/run/secrets/role_bridge_token,readonly",
               "-e","DB_PATH=/state/panel.db","-e","PUBLIC_ORIGIN="+origin,
               "minecraft-bungee-panel:test")
        for _ in range(60):
            try:
                urllib.request.urlopen(origin+"/healthz",timeout=1);break
            except (OSError,urllib.error.URLError): time.sleep(0.5)
        else: raise RuntimeError("panel failed to become healthy")
        with urllib.request.urlopen(origin) as response:
            assert "/assets/" in response.read().decode(), "production React assets were not embedded"
            assert "unsafe-inline" not in response.headers.get("Content-Security-Policy", "")
        api("/servers",expected=401)
        session=api("/login","POST",{"username":"admin","password":password})
        csrf=session["csrf"]
        panel_info=json.loads(docker("inspect",stack+"-panel"))[0]
        panel_ip=panel_info["NetworkSettings"]["Networks"][stack]["IPAddress"]
        bridge_token=(temp/"secrets"/"role_bridge_token").read_text()
        player_id="12345678-1234-4234-8234-123456789abc"
        def bridge(server):
            body={"server":server,"players":[{"uuid":player_id,"name":"SmokePlayer","lastSeen":0}]}
            req=urllib.request.Request(f"http://{panel_ip}:8083/bridge/sync",data=json.dumps(body).encode(),headers={"Authorization":"Bearer "+bridge_token,"Content-Type":"application/json"})
            with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
        assert bridge("lobby")["assignments"][player_id]=="player"
        assert next(r for r in api("/roles") if r["id"]=="player")["name"]=="Yuyu's Bat"
        assert all(not commands for commands in api("/role-commands")["roles"].values())
        api("/roles/mod/commands", "PUT", {"commands":["op"]}, csrf, expected=400)
        api("/roles/mod/commands", "PUT", {"commands":["kick","fly"]}, csrf)
        assert api("/role-commands")["roles"]["mod"] == ["fly","kick"]
        api("/players/"+player_id+"/role","PUT",{"role":"owner"},expected=403)
        api("/players/"+player_id+"/role","PUT",{"role":"op"},csrf,400)
        api("/players/"+player_id+"/role","PUT",{"role":"owner"},csrf)
        assert bridge("survival")["assignments"][player_id]=="owner"
        assert api("/players?role=owner")["total"]==1
        services=api("/servers")
        assert len(services)==4
        assert next(s for s in services if s["id"]=="creative")["state"]=="running"
        assert next(s for s in services if s["id"]=="lobby")["state"]=="unavailable"
        api("/servers/creative/stop","POST",expected=403)
        req=urllib.request.Request(origin+"/api/v1/servers/creative/console")
        with opener.open(req,timeout=10) as stream:
            assert stream.readline().decode().strip()=="event: snapshot"
            data=json.loads(stream.readline().decode().removeprefix("data: "))
            data="\n".join(data)
            assert "<script>" in data and "[redacted]" in data and token not in data
        api("/servers/creative/stop","POST",csrf=csrf)
        time.sleep(1)
        assert docker("inspect","--format","{{.State.Status}}",stack+"-creative")=="exited"
        api("/servers/creative/start","POST",csrf=csrf)
        api("/servers/creative/restart","POST",csrf=csrf)
        api("/servers/velocity/command","POST",{"command":"list"},csrf,400)
        api("/servers/creative/exec","POST",csrf=csrf,expected=400)
        docker("restart",stack+"-panel")
        for _ in range(30):
            try:
                assert api("/session")["username"]=="admin"; break
            except (OSError,urllib.error.URLError):time.sleep(0.5)
        else:raise RuntimeError("session did not survive panel restart")
        assert api("/players?role=owner")["players"][0]["role"]=="owner"
        assert api("/role-commands")["roles"]["mod"] == ["fly","kick"]
        api("/logout","POST",csrf=csrf)
        api("/session",expected=401)
        print("PASS: login/CSRF, real Docker status/logs/start/stop/restart, secret redaction, unavailable servers, session persistence/logout, cosmetic role synchronization/persistence")
        if keep:
            (temp/"preview.json").write_text(json.dumps({"origin":origin,"containers":containers,"network":stack}))
            print(f"Preview: {origin}; temporary resources: {temp}")
    finally:
        if not keep:
            for name in reversed(containers):
                subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(["docker","network","rm",stack],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            shutil.rmtree(temp,ignore_errors=True)

if __name__ == "__main__":
    os.chdir(ROOT)
    main()
